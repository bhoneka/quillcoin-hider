// quillcoin-recorder: records ONE window of ONE program, with that program's own sound, into an mp4.
//
// The hiding tool starts it when a run starts and stops it when the run is over. It never records the screen,
// other windows, notifications or the microphone: only the game's window and what the game itself plays.
//
//   quillcoin-recorder --pid <pid of the game> --out <file.mp4> [--width 1920] [--fps 30] [--max <seconds>]
//   quillcoin-recorder --pid <pid of the game> --selftest      records two seconds, checks them, keeps nothing
//
// It stops when a line arrives on its input, when its input closes (the game quit), or on SIGINT / SIGTERM.
// What it prints, one line each:
//   WINDOW <width>x<height>     the window it found
//   READY                       recording
//   DONE <seconds>              the file is complete
//   SELFTEST OK ...             the two seconds were fine
//   NOPERMISSION                macOS has not been told yet that this may record (System Settings > Privacy & Security > Screen & System Audio Recording)
//   ERROR <what went wrong>
import Foundation
import ScreenCaptureKit
import AVFoundation
import CoreMedia
import CoreGraphics
import AppKit

setvbuf(stdout, nil, _IOLBF, 0)
_ = NSApplication.shared                                                                     // a plain program has to meet the window system before it may ask it for anything
func say(_ s: String) { print(s); fflush(stdout) }
func die(_ s: String, _ code: Int32) -> Never { say(s); exit(code) }

var pid: pid_t = 0, out = "", maxWidth = 1920, fps = 30, maxSeconds = 3600.0, selftest = false
var it = CommandLine.arguments.dropFirst().makeIterator()
while let a = it.next() {
    switch a {
    case "--pid": pid = pid_t(it.next() ?? "") ?? 0
    case "--out": out = it.next() ?? ""
    case "--width": maxWidth = Int(it.next() ?? "") ?? 1920
    case "--fps": fps = Int(it.next() ?? "") ?? 30
    case "--max": maxSeconds = Double(it.next() ?? "") ?? 3600
    case "--selftest": selftest = true
    default: die("ERROR unknown option " + a, 2)
    }
}
if pid <= 0 || (out.isEmpty && !selftest) { die("ERROR usage: quillcoin-recorder --pid <pid> --out <file.mp4> | --selftest", 2) }
if selftest { out = NSTemporaryDirectory() + "quillcoin-selftest-\(getpid()).mp4" }
if FileManager.default.fileExists(atPath: out) { die("ERROR the file exists already", 2) }

final class Recorder: NSObject, SCStreamDelegate, SCStreamOutput, SCRecordingOutputDelegate {
    var stream: SCStream?
    var output: SCRecordingOutput?
    var began: Date?
    var stopping = false
    let lock = NSLock()

    func recordingOutputDidStartRecording(_ o: SCRecordingOutput) { began = Date(); say("READY") }
    func recordingOutput(_ o: SCRecordingOutput, didFailWithError error: Error) {
        lock.lock(); let ours = stopping; lock.unlock()
        if ours { finished() } else { die("ERROR " + error.localizedDescription, 4) }          // being stopped is reported through here as well
    }
    func recordingOutputDidFinishRecording(_ o: SCRecordingOutput) { finished() }
    func stream(_ s: SCStream, didStopWithError error: Error) {
        lock.lock(); let ours = stopping; lock.unlock()
        if !ours { say("ERROR the window went away: " + error.localizedDescription) }
        DispatchQueue.global().asyncAfter(deadline: .now() + 1.5) { self.finished() }       // the file is closed by then
    }
    func stream(_ s: SCStream, didOutputSampleBuffer b: CMSampleBuffer, of type: SCStreamOutputType) { }

    func stop() {
        lock.lock(); if stopping { lock.unlock(); return }; stopping = true; lock.unlock()
        guard let s = stream else { return }                                                  // not recording yet: the start sees `stopping` and ends at once
        Task {
            try? await s.stopCapture()                                                       // closes the file
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            self.finished()                                                                  // in case nobody said it was finished
        }
    }

    var reported = false
    func finished() {
        lock.lock(); if reported { lock.unlock(); return }; reported = true; lock.unlock()
        let seconds = began.map { Date().timeIntervalSince($0) } ?? 0
        if selftest { Task { await check(seconds) } } else { say(String(format: "DONE %.1f", seconds)); exit(0) }
    }

    func check(_ seconds: Double) async {
        let url = URL(fileURLWithPath: out)
        defer { try? FileManager.default.removeItem(at: url) }
        let asset = AVURLAsset(url: url)
        let video = (try? await asset.loadTracks(withMediaType: .video)) ?? []
        let audio = (try? await asset.loadTracks(withMediaType: .audio)) ?? []
        let length = ((try? await asset.load(.duration)) ?? .zero).seconds
        var size = CGSize.zero
        if let v = video.first { size = (try? await v.load(.naturalSize)) ?? .zero }
        try? FileManager.default.removeItem(at: url)
        if video.isEmpty || length < 0.5 { die("ERROR the test recording holds no picture", 5) }
        say(String(format: "SELFTEST OK %dx%d, %.1f s, sound %@", Int(size.width), Int(size.height), length, audio.isEmpty ? "NOT recorded" : "recorded"))
        exit(0)
    }
}

let recorder = Recorder()
var sources: [DispatchSourceSignal] = []

Task {
    if !CGPreflightScreenCaptureAccess() {
        _ = CGRequestScreenCaptureAccess()                                                  // macOS asks once; after that it has to be switched on in System Settings
        die("NOPERMISSION", 3)
    }
    let content: SCShareableContent
    do { content = try await SCShareableContent.excludingDesktopWindows(true, onScreenWindowsOnly: false) }
    catch { die("NOPERMISSION " + error.localizedDescription, 3) }
    // the program's biggest window, whatever level it sits on (a game in fullscreen sits above the menu bar), one that is on screen if there is one
    let mine = content.windows.filter { $0.owningApplication?.processID == pid && $0.frame.width >= 320 && $0.frame.height >= 240 }
    let area: (SCWindow) -> CGFloat = { ($0.isOnScreen ? 1e9 : 0) + $0.frame.width * $0.frame.height }
    guard let window = mine.max(by: { area($0) < area($1) }) else { die("ERROR no window of that program was found", 6) }

    let filter = SCContentFilter(desktopIndependentWindow: window)
    let scale = CGFloat(filter.pointPixelScale)
    var w = window.frame.width * scale, h = window.frame.height * scale
    if w > CGFloat(maxWidth) { h = (h * CGFloat(maxWidth) / w).rounded(); w = CGFloat(maxWidth) }
    let cfg = SCStreamConfiguration()
    cfg.width = max(2, Int(w) & ~1); cfg.height = max(2, Int(h) & ~1)
    cfg.minimumFrameInterval = CMTime(value: 1, timescale: CMTimeScale(max(1, fps)))
    cfg.showsCursor = false
    cfg.queueDepth = 6
    cfg.capturesAudio = true                                                                 // the sound of the program that owns the window, nothing else
    cfg.excludesCurrentProcessAudio = true
    cfg.sampleRate = 48000; cfg.channelCount = 2
    say("WINDOW \(cfg.width)x\(cfg.height)")

    let stream = SCStream(filter: filter, configuration: cfg, delegate: recorder)
    let rc = SCRecordingOutputConfiguration()
    rc.outputURL = URL(fileURLWithPath: out)
    rc.outputFileType = .mp4
    rc.videoCodecType = .h264
    let ro = SCRecordingOutput(configuration: rc, delegate: recorder)
    do {
        let q = DispatchQueue(label: "quillcoin.recorder")
        try stream.addStreamOutput(recorder, type: .screen, sampleHandlerQueue: q)
        try stream.addStreamOutput(recorder, type: .audio, sampleHandlerQueue: q)
        try stream.addRecordingOutput(ro)
        recorder.stream = stream; recorder.output = ro
        try await stream.startCapture()
    } catch { die("ERROR " + error.localizedDescription, 4) }
    recorder.lock.lock(); let early = recorder.stopping; recorder.lock.unlock()
    if early { recorder.lock.lock(); recorder.stopping = false; recorder.lock.unlock(); recorder.stop() }

    DispatchQueue.global().asyncAfter(deadline: .now() + (selftest ? 2.5 : maxSeconds)) { recorder.stop() }
}

// anything on the input, or the input closing, ends the recording
if !selftest {
    Thread { _ = readLine(); recorder.stop() }.start()
}
for s in [SIGINT, SIGTERM] {
    signal(s, SIG_IGN)
    let src = DispatchSource.makeSignalSource(signal: s, queue: .global())
    src.setEventHandler { recorder.stop() }
    src.resume()
    sources.append(src)
}
dispatchMain()
