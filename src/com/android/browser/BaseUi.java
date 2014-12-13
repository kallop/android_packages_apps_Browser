/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.browser;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.PaintDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.text.TextUtils;
import android.view.ActionMode;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebChromeClient.CustomViewCallback;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Toast;
import android.content.res.TypedArray;
import com.android.browser.R;
import com.android.browser.Tab.SecurityState;

import org.codeaurora.swe.WebView;

import java.util.List;

/**
 * UI interface definitions
 */
public abstract class BaseUi implements UI {

    protected static final boolean ENABLE_BORDER_AROUND_FAVICON = false;

    protected static final FrameLayout.LayoutParams COVER_SCREEN_PARAMS =
        new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT);

    protected static final FrameLayout.LayoutParams COVER_SCREEN_GRAVITY_CENTER =
        new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
        Gravity.CENTER);

    private static final int MSG_HIDE_TITLEBAR = 1;
    public static final int HIDE_TITLEBAR_DELAY = 1500; // in ms

    Activity mActivity;
    UiController mUiController;
    TabControl mTabControl;
    protected Tab mActiveTab;
    private InputMethodManager mInputManager;

    private Drawable mLockIconSecure;
    private Drawable mLockIconMixed;
    private Drawable mGenericFavicon;

    protected FrameLayout mContentView;
    protected FrameLayout mCustomViewContainer;

    private View mCustomView;
    private CustomViewCallback mCustomViewCallback;
    private int mOriginalOrientation;

    private UrlBarAutoShowManager mUrlBarAutoShowManager;

    private Toast mStopToast;

    // the default <video> poster
    private Bitmap mDefaultVideoPoster;
    // the video progress view
    private View mVideoProgressView;

    private final View mDecorView;

    private boolean mActivityPaused;
    protected TitleBar mTitleBar;
    private NavigationBarBase mNavigationBar;
    private boolean mBlockFocusAnimations;

    public BaseUi(Activity browser, UiController controller) {
        mActivity = browser;
        mUiController = controller;
        mTabControl = controller.getTabControl();
        mInputManager = (InputMethodManager)
                browser.getSystemService(Activity.INPUT_METHOD_SERVICE);
        // This assumes that the top-level root of our layout has the 'android.R.id.content' id
        // it's used in place of setContentView because we're attaching a <merge> here.
        FrameLayout frameLayout = (FrameLayout) mActivity.getWindow()
                .getDecorView().findViewById(android.R.id.content);
        LayoutInflater.from(mActivity)
                .inflate(R.layout.custom_screen, frameLayout);
        mContentView = (FrameLayout) frameLayout.findViewById(
                R.id.main_content);
        mCustomViewContainer = (FrameLayout) frameLayout.findViewById(
                R.id.fullscreen_custom_content);
        setFullscreen(BrowserSettings.getInstance().useFullscreen());
        mTitleBar = new TitleBar(mActivity, mUiController, this,
                mContentView);
        mTitleBar.setProgress(100);
        mNavigationBar = mTitleBar.getNavigationBar();
        mUrlBarAutoShowManager = new UrlBarAutoShowManager(this);

        // install system ui visibility listeners
        mDecorView = mActivity.getWindow().getDecorView();
        mDecorView.setOnSystemUiVisibilityChangeListener(mSystemUiVisibilityChangeListener);
    }

    private View.OnSystemUiVisibilityChangeListener mSystemUiVisibilityChangeListener =
            new View.OnSystemUiVisibilityChangeListener() {
                @Override
                public void onSystemUiVisibilityChange(int visFlags) {
                    final boolean lostFullscreen = (visFlags & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0;
                    if (lostFullscreen)
                        setFullscreen(BrowserSettings.getInstance().useFullscreen());
                }
            };

    private void cancelStopToast() {
        if (mStopToast != null) {
            mStopToast.cancel();
            mStopToast = null;
        }
    }

    private Drawable getLockIconSecure() {
        if (mLockIconSecure == null) {
            mLockIconSecure = mActivity.getResources().getDrawable(R.drawable.ic_secure_holo_dark);
        }
        return mLockIconSecure;
    }

    private Drawable getLockIconMixed() {
        if (mLockIconMixed == null) {
            mLockIconMixed = mActivity.getResources().getDrawable(R.drawable.ic_secure_partial_holo_dark);
        }
        return mLockIconMixed;
    }

    protected Drawable getGenericFavicon() {
        if (mGenericFavicon == null) {
            mGenericFavicon = mActivity.getResources().getDrawable(R.drawable.ic_deco_favicon_normal);
        }
        return mGenericFavicon;
    }

    // lifecycle

    public void onPause() {
        if (isCustomViewShowing()) {
            onHideCustomView();
        }
        if (mTabControl.getCurrentTab() != null) {
            mTabControl.getCurrentTab().exitFullscreen();
        }
        cancelStopToast();
        mActivityPaused = true;
    }

    public void onResume() {
        mActivityPaused = false;
        // check if we exited without setting active tab
        // b: 5188145
        setFullscreen(BrowserSettings.getInstance().useFullscreen());
        final Tab ct = mTabControl.getCurrentTab();
        if (ct != null) {
            setActiveTab(ct);
        }
        mTitleBar.onResume();
    }

    protected boolean isActivityPaused() {
        return mActivityPaused;
    }

    public void onConfigurationChanged(Configuration config) {
    }

    public Activity getActivity() {
        return mActivity;
    }

    // key handling

    @Override
    public boolean onBackKey() {
        if (mCustomView != null) {
            mUiController.hideCustomView();
            return true;
        } else if ((mTabControl.getCurrentTab() != null) &&
                (mTabControl.getCurrentTab().exitFullscreen())) {
            return true;
        }
        return false;
    }

    @Override
    public boolean onMenuKey() {
        return false;
    }

    // Tab callbacks
    @Override
    public void onTabDataChanged(Tab tab) {
        setUrlTitle(tab);
        setFavicon(tab);
        updateLockIconToLatest(tab);
        updateNavigationState(tab);
        mTitleBar.onTabDataChanged(tab);
        mNavigationBar.onTabDataChanged(tab);
        onProgressChanged(tab);
    }

    @Override
    public void onProgressChanged(Tab tab) {
        int progress = tab.getLoadProgress();
        if (tab.inForeground()) {
            mTitleBar.setProgress(progress);
        }
    }

    @Override
    public void bookmarkedStatusHasChanged(Tab tab) {
        if (tab.inForeground()) {
            boolean isBookmark = tab.isBookmarkedSite();
            mNavigationBar.setCurrentUrlIsBookmark(isBookmark);
        }
    }

    @Override
    public void onPageStopped(Tab tab) {
        cancelStopToast();
        if (tab.inForeground()) {
            mStopToast = Toast
                    .makeText(mActivity, R.string.stopping, Toast.LENGTH_SHORT);
            mStopToast.show();
        }
    }

    @Override
    public boolean needsRestoreAllTabs() {
        return true;
    }

    @Override
    public void addTab(Tab tab) {
    }

    @Override
    public void setActiveTab(final Tab tab) {
        if (tab == null) return;
        Tab tabToRemove = null;
        Tab tabToWaitFor = null;

        // block unnecessary focus change animations during tab switch
        mBlockFocusAnimations = true;
        mHandler.removeMessages(MSG_HIDE_TITLEBAR);
        if ((tab != mActiveTab) && (mActiveTab != null)) {
            tabToRemove = mActiveTab;
            WebView web = mActiveTab.getWebView();
            if (web != null) {
                web.setOnTouchListener(null);
            }
        }
        mActiveTab = tab;
        BrowserWebView web = (BrowserWebView) mActiveTab.getWebView();
        updateUrlBarAutoShowManagerTarget();
        attachTabToContentView(tab);
        if (web != null) {
            // Request focus on the top window.
            web.setTitleBar(mTitleBar);
            mTitleBar.onScrollChanged();
            tabToWaitFor = mActiveTab;
        }
        mTitleBar.bringToFront();
        tab.getTopWindow().requestFocus();
        onTabDataChanged(tab);
        onProgressChanged(tab);
        mNavigationBar.setIncognitoMode(tab.isPrivateBrowsingEnabled());
        mBlockFocusAnimations = false;

        scheduleRemoveTab(tabToRemove, tabToWaitFor);
    }

    Tab mTabToRemove = null;
    Tab mTabToWaitFor = null;
    int mNumRemoveTries = 0;
    Runnable mRunnable = null;

    protected void scheduleRemoveTab(Tab tabToRemove, Tab tabToWaitFor) {
        //remove previously scehduled tab
        if (mTabToRemove != null) {
            if (mRunnable != null)
               mTitleBar.removeCallbacks(mRunnable);
            removeTabFromContentView(mTabToRemove);
            mTabToRemove.performPostponedDestroy();
            mRunnable = null;
        }
        mTabToRemove = tabToRemove;
        mTabToWaitFor = tabToWaitFor;
        mNumRemoveTries = 0;

        if (mTabToRemove != null) {
            mTabToRemove.postponeDestroy();
            tryRemoveTab();
        }
    }

    protected void tryRemoveTab() {
        mNumRemoveTries++;
        // Ensure the webview is still valid
        if (mNumRemoveTries < 20 && mTabToWaitFor.getWebView() != null) {
            if (!mTabToWaitFor.getWebView().isReady()) {
                if (mRunnable == null) {
                    mRunnable = new Runnable() {
                        public void run() {
                            tryRemoveTab();
                        }
                    };
                }
                /*if the new tab is still not ready, wait another 2 frames
                  before trying again.  1 frame for the tab to render the first
                  frame, another 1 frame to make sure the swap is done*/
                mTitleBar.postDelayed(mRunnable, 33);
                return;
            }
        }
        if (mTabToRemove != null) {
            if (mRunnable != null)
                mTitleBar.removeCallbacks(mRunnable);
            removeTabFromContentView(mTabToRemove);
            mTabToRemove.performPostponedDestroy();
            mRunnable = null;
        }
        mTabToRemove = null;
        mTabToWaitFor = null;
    }

    protected void updateUrlBarAutoShowManagerTarget() {
        WebView web = mActiveTab != null ? mActiveTab.getWebView() : null;
        if (web instanceof BrowserWebView) {
            mUrlBarAutoShowManager.setTarget((BrowserWebView) web);
        }
    }

    Tab getActiveTab() {
        return mActiveTab;
    }

    @Override
    public void updateTabs(List<Tab> tabs) {
    }

    @Override
    public void removeTab(Tab tab) {
        if (mActiveTab == tab) {
            removeTabFromContentView(tab);
            mActiveTab = null;
        }
    }

    @Override
    public void detachTab(Tab tab) {
        removeTabFromContentView(tab);
    }

    @Override
    public void attachTab(Tab tab) {
        attachTabToContentView(tab);
    }

    protected void attachTabToContentView(Tab tab) {
        if ((tab == null) || (tab.getWebView() == null)) {
            return;
        }
        View container = tab.getViewContainer();
        WebView mainView  = tab.getWebView();
        // Attach the WebView to the container and then attach the
        // container to the content view.
        FrameLayout wrapper =
                (FrameLayout) container.findViewById(R.id.webview_wrapper);
        ViewGroup parent = (ViewGroup) mainView.getParent();
        if (parent != wrapper) {
            if (parent != null) {
                parent.removeView(mainView);
            }
            wrapper.addView(mainView);
        }
        parent = (ViewGroup) container.getParent();
        if (parent != mContentView) {
            if (parent != null) {
                parent.removeView(container);
            }
            mContentView.addView(container, COVER_SCREEN_PARAMS);
        }
        mUiController.attachSubWindow(tab);
    }

    private void removeTabFromContentView(Tab tab) {
        hideTitleBar();
        // Remove the container that contains the main WebView.
        WebView mainView = tab.getWebView();
        View container = tab.getViewContainer();
        if (mainView == null) {
            return;
        }
        // Remove the container from the content and then remove the
        // WebView from the container. This will trigger a focus change
        // needed by WebView.
        FrameLayout wrapper =
                (FrameLayout) container.findViewById(R.id.webview_wrapper);
        wrapper.removeView(mainView);
        mContentView.removeView(container);
        mUiController.endActionMode();
        mUiController.removeSubWindow(tab);
    }

    @Override
    public void onSetWebView(Tab tab, WebView webView) {
        View container = tab.getViewContainer();
        if (container == null) {
            // The tab consists of a container view, which contains the main
            // WebView, as well as any other UI elements associated with the tab.
            container = mActivity.getLayoutInflater().inflate(R.layout.tab,
                    mContentView, false);
            tab.setViewContainer(container);
        }
        if (tab.getWebView() != webView) {
            // Just remove the old one.
            FrameLayout wrapper =
                    (FrameLayout) container.findViewById(R.id.webview_wrapper);
            wrapper.removeView(tab.getWebView());
        }
    }

    /**
     * create a sub window container and webview for the tab
     * Note: this methods operates through side-effects for now
     * it sets both the subView and subViewContainer for the given tab
     * @param tab tab to create the sub window for
     * @param subView webview to be set as a subwindow for the tab
     */
    @Override
    public void createSubWindow(Tab tab, WebView subView) {
        View subViewContainer = mActivity.getLayoutInflater().inflate(
                R.layout.browser_subwindow, null);
        ViewGroup inner = (ViewGroup) subViewContainer
                .findViewById(R.id.inner_container);
        inner.addView(subView, new LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT));
        final ImageButton cancel = (ImageButton) subViewContainer
                .findViewById(R.id.subwindow_close);
        final WebView cancelSubView = subView;
        cancel.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                ((BrowserWebView) cancelSubView).getWebChromeClient().onCloseWindow(cancelSubView);
            }
        });
        tab.setSubWebView(subView);
        tab.setSubViewContainer(subViewContainer);
    }

    /**
     * Remove the sub window from the content view.
     */
    @Override
    public void removeSubWindow(View subviewContainer) {
        mContentView.removeView(subviewContainer);
        mUiController.endActionMode();
    }

    /**
     * Attach the sub window to the content view.
     */
    @Override
    public void attachSubWindow(View container) {
        if (container.getParent() != null) {
            // already attached, remove first
            ((ViewGroup) container.getParent()).removeView(container);
        }
        mContentView.addView(container, COVER_SCREEN_PARAMS);
    }

    protected void refreshWebView() {
        WebView web = getWebView();
        if (web != null) {
            web.invalidate();
        }
    }

    public void editUrl(boolean clearInput, boolean forceIME) {
        if (mUiController.isInCustomActionMode()) {
            mUiController.endActionMode();
        }
        showTitleBar();
        if ((getActiveTab() != null) && !getActiveTab().isSnapshot()) {
            mNavigationBar.startEditingUrl(clearInput, forceIME);
        }
    }

    boolean canShowTitleBar() {
        return !isTitleBarShowing()
                && !isActivityPaused()
                && (getActiveTab() != null)
                && (getWebView() != null)
                && !mUiController.isInCustomActionMode();
    }

    protected void showTitleBar() {
        mHandler.removeMessages(MSG_HIDE_TITLEBAR);
        if (canShowTitleBar()) {
            mTitleBar.show();
        }
    }

    protected void hideTitleBar() {
        if (mTitleBar.isShowing()) {
            mTitleBar.hide();
        }
    }

    protected boolean isTitleBarShowing() {
        return mTitleBar.isShowing();
    }

    public boolean isEditingUrl() {
        return mTitleBar.isEditingUrl();
    }

    public void stopEditingUrl() {
        mTitleBar.getNavigationBar().stopEditingUrl();
    }

    public TitleBar getTitleBar() {
        return mTitleBar;
    }

    @Override
    public void showComboView(ComboViews startingView, Bundle extras) {
        Intent intent = new Intent(mActivity, ComboViewActivity.class);
        intent.putExtra(ComboViewActivity.EXTRA_INITIAL_VIEW, startingView.name());
        intent.putExtra(ComboViewActivity.EXTRA_COMBO_ARGS, extras);
        Tab t = getActiveTab();
        if (t != null) {
            intent.putExtra(ComboViewActivity.EXTRA_CURRENT_URL, t.getUrl());
        }
        mActivity.startActivityForResult(intent, Controller.COMBO_VIEW);
    }

    @Override
    public void showCustomView(View view, int requestedOrientation,
            CustomViewCallback callback) {
        // if a view already exists then immediately terminate the new one
        if (mCustomView != null) {
            callback.onCustomViewHidden();
            return;
        }
        mOriginalOrientation = mActivity.getRequestedOrientation();
        FrameLayout decor = (FrameLayout) mActivity.getWindow().getDecorView();
        decor.addView(view, COVER_SCREEN_PARAMS);
        mCustomView = view;
        showFullscreen(true);
        ((BrowserWebView) getWebView()).setVisibility(View.INVISIBLE);
        mCustomViewCallback = callback;
        mActivity.setRequestedOrientation(requestedOrientation);
    }

    @Override
    public void onHideCustomView() {
        ((BrowserWebView) getWebView()).setVisibility(View.VISIBLE);
        if (mCustomView == null)
            return;
        showFullscreen(false);
        FrameLayout decor = (FrameLayout) mActivity.getWindow().getDecorView();
        decor.removeView(mCustomView);
        mCustomView = null;
        mCustomViewCallback.onCustomViewHidden();
        // Show the content view.
        mActivity.setRequestedOrientation(mOriginalOrientation);
    }

    @Override
    public boolean isCustomViewShowing() {
        return mCustomView != null;
    }

    protected void dismissIME() {
        if (mInputManager.isActive()) {
            mInputManager.hideSoftInputFromWindow(mContentView.getWindowToken(),
                    0);
        }
    }

    @Override
    public boolean isWebShowing() {
        return mCustomView == null;
    }

    // -------------------------------------------------------------------------

    protected void updateNavigationState(Tab tab) {
    }

    /**
     * Update the lock icon to correspond to our latest state.
     */
    protected void updateLockIconToLatest(Tab t) {
        if (t != null && t.inForeground()) {
            updateLockIconImage(t.getSecurityState());
        }
    }

    /**
     * Updates the lock-icon image in the title-bar.
     */
    private void updateLockIconImage(SecurityState securityState) {
        Drawable d = null;
        if (securityState == SecurityState.SECURITY_STATE_SECURE) {
            d = getLockIconSecure();
        } else if (securityState == SecurityState.SECURITY_STATE_MIXED
                || securityState == SecurityState.SECURITY_STATE_BAD_CERTIFICATE) {
            // TODO: It would be good to have different icons for insecure vs mixed content.
            // See http://b/5403800
            d = getLockIconMixed();
        }
        mNavigationBar.setLock(d);
    }

    protected void setUrlTitle(Tab tab) {
        String url = tab.getUrl();
        String title = tab.getTitle();
        if (TextUtils.isEmpty(title)) {
            title = url;
        }
        if (tab.inForeground()) {
            mNavigationBar.setDisplayTitle(url);
        }
    }

    // Set the favicon in the title bar.
    protected void setFavicon(Tab tab) {
        if (tab.inForeground()) {
            Bitmap icon = tab.getFavicon();
            mNavigationBar.setFavicon(icon);
        }
    }

    // active tabs page

    public void showActiveTabsPage() {
    }

    /**
     * Remove the active tabs page.
     */
    public void removeActiveTabsPage() {
    }

    // menu handling callbacks

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        return true;
    }

    @Override
    public void updateMenuState(Tab tab, Menu menu) {
    }

    @Override
    public void onOptionsMenuOpened() {
    }

    @Override
    public void onExtendedMenuOpened() {
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        return false;
    }

    @Override
    public void onOptionsMenuClosed(boolean inLoad) {
    }

    @Override
    public void onExtendedMenuClosed(boolean inLoad) {
    }

    @Override
    public void onContextMenuCreated(Menu menu) {
    }

    @Override
    public void onContextMenuClosed(Menu menu, boolean inLoad) {
    }

    // -------------------------------------------------------------------------
    // Helper function for WebChromeClient
    // -------------------------------------------------------------------------

    @Override
    public Bitmap getDefaultVideoPoster() {
        if (mDefaultVideoPoster == null) {
            mDefaultVideoPoster = BitmapFactory.decodeResource(
                    mActivity.getResources(), R.drawable.default_video_poster);
        }
        return mDefaultVideoPoster;
    }

    @Override
    public View getVideoLoadingProgressView() {
        if (mVideoProgressView == null) {
            LayoutInflater inflater = LayoutInflater.from(mActivity);
            mVideoProgressView = inflater.inflate(
                    R.layout.video_loading_progress, null);
        }
        return mVideoProgressView;
    }

    @Override
    public void showMaxTabsWarning() {
        Toast warning = Toast.makeText(mActivity,
                mActivity.getString(R.string.max_tabs_warning),
                Toast.LENGTH_SHORT);
        warning.show();
    }

    protected WebView getWebView() {
        if (mActiveTab != null) {
            return mActiveTab.getWebView();
        } else {
            return null;
        }
    }

    public void setFullscreen(boolean enabled) {
        Window win = mActivity.getWindow();
        WindowManager.LayoutParams winParams = win.getAttributes();
        final int bits = WindowManager.LayoutParams.FLAG_FULLSCREEN;
        final int fullscreenImmersiveSetting =
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                        View.SYSTEM_UI_FLAG_FULLSCREEN |
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;

        if (mCustomView != null) {
            mCustomView.setSystemUiVisibility(enabled ?
                    fullscreenImmersiveSetting : View.SYSTEM_UI_FLAG_VISIBLE);
        } else if (Build.VERSION.SDK_INT >= 19) {
            mContentView.setSystemUiVisibility(enabled ?
                    fullscreenImmersiveSetting  : View.SYSTEM_UI_FLAG_VISIBLE);
        } else {
            mContentView.setSystemUiVisibility(enabled ?
                    View.SYSTEM_UI_FLAG_LOW_PROFILE  : View.SYSTEM_UI_FLAG_VISIBLE);
        }
        if (enabled)
            winParams.flags |=  bits;
        else
            winParams.flags &= ~bits;

        win.setAttributes(winParams);
    }

    //make full screen by showing/hiding topbar and system status bar
    public void showFullscreen(boolean fullScreen) {
        //Hide/show system ui bar as needed
        if (!BrowserSettings.getInstance().useFullscreen())
            setFullscreen(fullScreen);

        //Hide/show topbar as needed
        if (getWebView() != null) {
            if (fullScreen) {
                //hide topbar
                getWebView().updateTopControls(true, false, false);
            } else {
                //show the topbar
                getWebView().updateTopControls(false, true, false);
                //enable for auto-hide
                if (!mTitleBar.isFixed())
                    getWebView().updateTopControls(true, true, false);
            }
        }
    }

    public void translateTitleBar(float topControlsOffsetYPix) {
        if (mTitleBar != null && !mInActionMode) {
            if (topControlsOffsetYPix != 0.0) {
                mTitleBar.setEnabled(false);
            } else {
                mTitleBar.setEnabled(true);
            }
            if (!mTitleBar.isFixed()) {
                float currentY = mTitleBar.getTranslationY();
                float height = mTitleBar.getHeight();
                if ((height + currentY) <= 0 && (height + topControlsOffsetYPix) > 0) {
                    mTitleBar.requestLayout();
                } else if ((height + topControlsOffsetYPix) <= 0) {
                    topControlsOffsetYPix -= 1;
                    mTitleBar.getParent().requestTransparentRegion(mTitleBar);
                }
            }
            // This was done to get HTML5 fullscreen API to work with fixed mode since
            // topcontrols are used to implement HTML5 fullscreen
            mTitleBar.setTranslationY(topControlsOffsetYPix);
        }
    }

    public Drawable getFaviconDrawable(Bitmap icon) {
        if (ENABLE_BORDER_AROUND_FAVICON) {
            Drawable[] array = new Drawable[3];
            array[0] = new PaintDrawable(Color.BLACK);
            PaintDrawable p = new PaintDrawable(Color.WHITE);
            array[1] = p;
            if (icon == null) {
                array[2] = getGenericFavicon();
            } else {
                array[2] = new BitmapDrawable(mActivity.getResources(), icon);
            }
            LayerDrawable d = new LayerDrawable(array);
            d.setLayerInset(1, 1, 1, 1, 1);
            d.setLayerInset(2, 2, 2, 2, 2);
            return d;
        }
        return icon == null ? getGenericFavicon() : new BitmapDrawable(mActivity.getResources(), icon);
    }

    public boolean isLoading() {
        return mActiveTab != null ? mActiveTab.inPageLoad() : false;
    }

    /**
     * Suggest to the UI that the title bar can be hidden. The UI will then
     * decide whether or not to hide based off a number of factors, such
     * as if the user is editing the URL bar or if the page is loading
     */
    public void suggestHideTitleBar() {
        if (!isLoading() && !isEditingUrl() && !mTitleBar.wantsToBeVisible()
                && !mNavigationBar.isMenuShowing()) {
            hideTitleBar();
        }
    }

    protected final void showTitleBarForDuration() {
        showTitleBarForDuration(HIDE_TITLEBAR_DELAY);
    }

    protected final void showTitleBarForDuration(long duration) {
        showTitleBar();
        Message msg = Message.obtain(mHandler, MSG_HIDE_TITLEBAR);
        mHandler.sendMessageDelayed(msg, duration);
    }

    protected void setMenuItemVisibility(Menu menu, int id,
                                         boolean visibility) {
        MenuItem item = menu.findItem(id);
        if (item != null) {
            item.setVisible(visibility);
        }
    }

    protected Handler mHandler = new Handler() {

        @Override
        public void handleMessage(Message msg) {
            if (msg.what == MSG_HIDE_TITLEBAR) {
                suggestHideTitleBar();
            }
            BaseUi.this.handleMessage(msg);
        }
    };

    protected void handleMessage(Message msg) {}

    @Override
    public void showWeb(boolean animate) {
        mUiController.hideCustomView();
    }

    public void setContentViewMarginTop(int margin) {
        FrameLayout.LayoutParams params =
                (FrameLayout.LayoutParams) mContentView.getLayoutParams();
        if (params.topMargin != margin) {
            params.topMargin = margin;
            mContentView.setLayoutParams(params);
        }
    }

    @Override
    public boolean blockFocusAnimations() {
        return mBlockFocusAnimations;
    }

    @Override
    public void onVoiceResult(String result) {
        mNavigationBar.onVoiceResult(result);
    }

    protected UiController getUiController() {
        return mUiController;
    }

    boolean mInActionMode = false;
    private float getActionModeHeight() {
        TypedArray actionBarSizeTypedArray = mActivity.obtainStyledAttributes(
                    new int[] { android.R.attr.actionBarSize });
        float size = actionBarSizeTypedArray.getDimension(0, 0f);
        actionBarSizeTypedArray.recycle();
        return size;
    }


    @Override
    public void onActionModeStarted(ActionMode mode) {
        mInActionMode = true;

        if (mTitleBar.isFixed()) {
            int fixedTbarHeight = mTitleBar.calculateEmbeddedHeight();
            setContentViewMarginTop(fixedTbarHeight);
        } else {
            mTitleBar.setTranslationY(getActionModeHeight());
        }
    }

    @Override
    public void onActionModeFinished(boolean inLoad) {
        mInActionMode = false;
        if (mTitleBar.isFixed()) {
            setContentViewMarginTop(0);
        } else {
            mTitleBar.setTranslationY(0);
        }
    }
}
