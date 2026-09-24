plugins {
    id("fabric-loom") version "1.9.2"
}

base {
    archivesName = properties["archives_base_name"] as String
    version = properties["mod_version"] as String
    group = properties["maven_group"] as String
}

repositories {
    maven { name = "meteor-maven"; url = uri("https://maven.meteordev.org/releases") }
    maven { name = "meteor-maven-snapshots"; url = uri("https://maven.meteordev.org/snapshots") }
}

dependencies {
    minecraft("com.mojang:minecraft:${properties["minecraft_version"] as String}")
    mappings("net.fabricmc:yarn:${properties["yarn_mappings"] as String}:v2")
    modImplementation("net.fabricmc:fabric-loader:${properties["loader_version"] as String}")
    modImplementation("meteordevelopment:meteor-client:${properties["minecraft_version"] as String}-SNAPSHOT")
    modCompileOnly("meteordevelopment:baritone:${properties["minecraft_version"] as String}-SNAPSHOT")   // runtime: the user's mods folder
}

tasks {
    processResources {
        val propertyMap = mapOf("version" to project.version, "mc_version" to project.property("minecraft_version"))
        inputs.properties(propertyMap)
        filesMatching("fabric.mod.json") { expand(propertyMap) }
    }
    jar {
        from("LICENSE") { rename { "${it}_quillcoin-hider" } }
    }
    java {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release = 21
    }
}
