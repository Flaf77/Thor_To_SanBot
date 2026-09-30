package com.thorbridge.sanbot.ui;

import android.content.Context;
import android.view.View;

import com.thorbridge.sanbot.App;
import com.thorbridge.sanbot.MainActivity;

public abstract class Page {

    protected final MainActivity act;
    protected final App app;
    private View root;

    protected Page(MainActivity act) {
        this.act = act;
        this.app = App.get();
    }

    public final View view() {
        if (root == null) root = build(act);
        return root;
    }

    protected abstract View build(Context c);

    public void onShow() {}

    public void onHide() {}

    /** Called ~4 times per second while the page is visible. */
    public abstract void refresh();

    public void onDestroy() {}
}
