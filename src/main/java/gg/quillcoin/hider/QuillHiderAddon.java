package gg.quillcoin.hider;

import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;

public class QuillHiderAddon extends MeteorAddon {
    public static final Category CATEGORY = new Category("QuillCoin");

    @Override
    public void onInitialize() {
        Modules.get().add(new QuillHider());
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "gg.quillcoin.hider";
    }
}
