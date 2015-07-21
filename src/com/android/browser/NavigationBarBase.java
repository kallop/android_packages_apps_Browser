/*
 * Copyright (C) 2011 The Android Open Source Project
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

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.SearchManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslCertificate;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnFocusChangeListener;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.Toast;

import com.android.browser.UrlInputView.UrlInputListener;
import com.android.browser.preferences.SiteSpecificPreferencesFragment;

import java.io.ByteArrayOutputStream;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;

import org.codeaurora.net.NetworkServices;
import org.codeaurora.swe.Engine;
import org.codeaurora.swe.WebRefiner;
import org.codeaurora.swe.WebView;
import org.codeaurora.swe.util.ColorUtils;

public class NavigationBarBase extends LinearLayout implements
        OnClickListener, UrlInputListener, OnFocusChangeListener,
        TextWatcher, UrlInputView.StateListener,
        PopupMenu.OnMenuItemClickListener, PopupMenu.OnDismissListener {

    private final static String TAG = "NavigationBarBase";

    protected BaseUi mBaseUi;
    protected TitleBar mTitleBar;
    protected UiController mUiController;
    protected UrlInputView mUrlInput;
    protected ImageView mStopButton;

    private SiteTileView mFaviconTile;
    private ImageView mMagnify;
    private View mVoiceButton;
    private ImageView mClearButton;
    private View mMore;
    private PopupMenu mPopupMenu;
    private boolean mOverflowMenuShowing;

    private static Bitmap mDefaultFavicon;

    private int mStatusBarColor;
    private static int mDefaultStatusBarColor = -1;

    private static final int WEBREFINER_COUNTER_MSG = 4242;
    private static final int WEBREFINER_COUNTER_MSG_DELAY = 3000;
    private Handler mHandler;

    private Tab.SecurityState mSecurityState = Tab.SecurityState.SECURITY_STATE_NOT_SECURE;

    private static final String noSitePrefs[] = {
            "chrome://",
            "about:",
            "content:",
    };

    public NavigationBarBase(Context context) {
        super(context);
    }

    public NavigationBarBase(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public NavigationBarBase(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mUrlInput = (UrlInputView) findViewById(R.id.url);
        mUrlInput.setUrlInputListener(this);
        mUrlInput.setOnFocusChangeListener(this);
        mUrlInput.setSelectAllOnFocus(true);
        mUrlInput.addTextChangedListener(this);
        mMore = findViewById(R.id.more_browser_settings);
        mMore.setOnClickListener(this);
        mMagnify = (ImageView) findViewById(R.id.magnify);
        mMagnify.setOnClickListener(this);
        mFaviconTile = (SiteTileView) findViewById(R.id.favicon_view);
        mFaviconTile.setOnClickListener(this);
        mVoiceButton = findViewById(R.id.voice);
        mVoiceButton.setOnClickListener(this);
        mClearButton = (ImageView) findViewById(R.id.clear);
        mClearButton.setOnClickListener(this);
        mStopButton = (ImageView) findViewById(R.id.stop);
        mStopButton.setOnClickListener(this);

        mDefaultFavicon = BitmapFactory.decodeResource(getResources(),
                R.drawable.ic_deco_favicon_normal);

        mHandler = new Handler() {
            @Override
            public void handleMessage(Message m) {
                switch (m.what) {
                    case WEBREFINER_COUNTER_MSG:
                        WebView wv = mUiController.getCurrentTopWebView();
                        if (wv != null && WebRefiner.isInitialized()) {
                            int count = WebRefiner.getInstance().getBlockedURLCount(wv);
                            if (count > 0) {
                                mFaviconTile.setBadgeBlockedObjectsCount(count);
                            }
                        }
                        mHandler.sendEmptyMessageDelayed(WEBREFINER_COUNTER_MSG,
                                WEBREFINER_COUNTER_MSG_DELAY);
                        break;
                }
            }
        };
    }

    public void setTitleBar(TitleBar titleBar) {
        mTitleBar = titleBar;
        mBaseUi = mTitleBar.getUi();
        mUiController = mTitleBar.getUiController();
        mUrlInput.setController(mUiController);
    }

    public void setSecurityState(Tab.SecurityState securityState) {
        mSecurityState = securityState;
        switch (mSecurityState) {
            case SECURITY_STATE_SECURE:
                mFaviconTile.setTrustLevel(SiteTileView.TRUST_TRUSTED);
                mFaviconTile.setBadgeHasCertIssues(false);
                break;
            case SECURITY_STATE_MIXED:
                mFaviconTile.setTrustLevel(SiteTileView.TRUST_UNTRUSTED);
                mFaviconTile.setBadgeHasCertIssues(true);
                break;
            case SECURITY_STATE_BAD_CERTIFICATE:
                mFaviconTile.setTrustLevel(SiteTileView.TRUST_AVOID);
                mFaviconTile.setBadgeHasCertIssues(true);
                break;
            case SECURITY_STATE_NOT_SECURE:
            default:
                mFaviconTile.setTrustLevel(SiteTileView.TRUST_UNKNOWN);
        }
    }

    public static int adjustColor(int color, float hueMultiplier,
                                   float saturationMultiplier, float valueMultiplier) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[0] *= hueMultiplier;
        hsv[1] *= saturationMultiplier;
        hsv[2] *= valueMultiplier;
        return Color.HSVToColor(Color.alpha(color), hsv);
    }

    public static void setStatusAndNavigationBarColor(final Activity activity, int color) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            int currentColor = activity.getWindow().getStatusBarColor();
            Integer from = currentColor;
            Integer to = color;
            ValueAnimator animator = ValueAnimator.ofObject(new ArgbEvaluator(), from, to);

            if (mDefaultStatusBarColor == -1) {
                mDefaultStatusBarColor = activity.getWindow().getStatusBarColor();
            }

            animator.addUpdateListener(
                    new ValueAnimator.AnimatorUpdateListener() {
                        @Override
                        public void onAnimationUpdate(ValueAnimator animation) {
                            Integer value = (Integer) animation.getAnimatedValue();
                            activity.getWindow().setStatusBarColor(value.intValue());
                            //activity.getWindow().setNavigationBarColor(value.intValue());
                        }
                    }
            );
            animator.start();
        }
    }

    private void updateSiteIconColor(String urlString, int color) {
        try {
            URL url = new URL(urlString);
            String host = url.getHost();
            SharedPreferences prefs = BrowserSettings.getInstance().getPreferences();
            int currentColor = prefs.getInt(host + ":color", 0);
            if (currentColor != color) {
                SharedPreferences.Editor editor = prefs.edit();
                editor.remove(host + ":color");
                editor.putInt(host + ":color", color);
                editor.commit();
            }
        } catch (MalformedURLException e) {
        }
    }

    public static int getSiteIconColor(String urlString) {
        try {
            URL url = new URL(urlString);
            String host = url.getHost();
            SharedPreferences prefs = BrowserSettings.getInstance().getPreferences();
            return prefs.getInt(host + ":color", 0);
        } catch (MalformedURLException e) {
            return 0;
        }
    }

    public static int getDefaultStatusBarColor() {
        return mDefaultStatusBarColor;
    }

    public void setFavicon(Bitmap icon) {
        int color = ColorUtils.getDominantColorForBitmap(icon);
        Tab tab = mUiController.getCurrentTab();

        if (tab != null) {
            if (tab.hasFavicon()) {
                updateSiteIconColor(tab.getUrl(), color);
                setStatusAndNavigationBarColor(mUiController.getActivity(),
                        adjustColor(color, 1, 1, 0.7f));
            } else {
                color = getSiteIconColor(tab.getUrl());
                if (color != 0) {
                    setStatusAndNavigationBarColor(mUiController.getActivity(),
                            adjustColor(color, 1, 1, 0.7f));
                } else {
                    setStatusAndNavigationBarColor(mUiController.getActivity(),
                            mDefaultStatusBarColor);
                }
            }
        } else {
            setStatusAndNavigationBarColor(mUiController.getActivity(), mDefaultStatusBarColor);
        }

        //Bitmap favicon = mUiController.getCurrentTopWebView().getFavicon();

        if (mFaviconTile != null) {
            mFaviconTile.replaceFavicon(mUiController.getCurrentTopWebView().getFavicon());
        }
    }

    protected void showSiteSpecificSettings() {
        WebView wv = mUiController.getCurrentTopWebView();
        int ads = 0;
        int tracker = 0;
        int malware = 0;

        WebRefiner webRefiner = WebRefiner.getInstance();
        if (wv != null &&  webRefiner != null) {
            WebRefiner.PageInfo pageInfo = webRefiner.getPageInfo(wv);
            if (pageInfo != null) {
                for (WebRefiner.MatchedURLInfo urlInfo : pageInfo.mMatchedURLInfoList) {
                    if (urlInfo.mActionTaken == WebRefiner.MatchedURLInfo.ACTION_BLOCKED) {
                        switch (urlInfo.mMatchedFilterCategory) {
                            case WebRefiner.RuleSet.CATEGORY_ADS:
                                ads++;
                                break;
                            case WebRefiner.RuleSet.CATEGORY_TRACKERS:
                                tracker++;
                                break;
                            case WebRefiner.RuleSet.CATEGORY_MALWARE_DOMAINS:
                                malware++;
                                break;
                        }
                    }
                }
            }
        }

        Bundle bundle = new Bundle();
        bundle.putCharSequence(SiteSpecificPreferencesFragment.EXTRA_SITE,
                mUiController.getCurrentTab().getUrl());

        bundle.putInt(SiteSpecificPreferencesFragment.EXTRA_WEB_REFINER_ADS_INFO, ads);
        bundle.putInt(SiteSpecificPreferencesFragment.EXTRA_WEB_REFINER_TRACKER_INFO, tracker);
        bundle.putInt(SiteSpecificPreferencesFragment.EXTRA_WEB_REFINER_MALWARE_INFO, malware);

        bundle.putParcelable(SiteSpecificPreferencesFragment.EXTRA_SECURITY_CERT,
                SslCertificate.saveState(wv.getCertificate()));

        SslError error = mUiController.getCurrentTab().getSslCertificateError();
        if (error != null) {
            int certError = 0;
            if (error.hasError(SslError.SSL_DATE_INVALID)) {
                certError |= (1 << SslError.SSL_DATE_INVALID);
            }

            if (error.hasError(SslError.SSL_EXPIRED)) {
                certError |= (1 << SslError.SSL_EXPIRED);
            }

            if (error.hasError(SslError.SSL_IDMISMATCH)) {
                certError |= (1 << SslError.SSL_IDMISMATCH);
            }

            if (error.hasError(SslError.SSL_INVALID)) {
                certError |= (1 << SslError.SSL_INVALID);
            }

            if (error.hasError(SslError.SSL_NOTYETVALID)) {
                certError |= (1 << SslError.SSL_NOTYETVALID);
            }

            if (error.hasError(SslError.SSL_UNTRUSTED)) {
                certError |= (1 << SslError.SSL_UNTRUSTED);
            }

            bundle.putInt(SiteSpecificPreferencesFragment.EXTRA_SECURITY_CERT_ERR, certError);
        }

        Bitmap favicon = mUiController.getCurrentTopWebView().getFavicon();
        if (favicon != null) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            favicon.compress(Bitmap.CompressFormat.PNG, 50, baos);
            bundle.putByteArray(SiteSpecificPreferencesFragment.EXTRA_FAVICON,
                    baos.toByteArray());
        }
        BrowserPreferencesPage.startPreferenceFragmentExtraForResult(
                mUiController.getActivity(),
                SiteSpecificPreferencesFragment.class.getName(), bundle, 0);
    }

    @Override
    public void onClick(View v) {
        Tab currentTab = mUiController.getCurrentTab();
        String url = null;
        if (currentTab != null){
            url = currentTab.getUrl();
        }
        if (mMore == v) {
            showMenu(mMore);
        } else if (mFaviconTile == v) {
            if (urlHasSitePrefs(url)){
                showSiteSpecificSettings();
            }
        } else if (mMagnify == v) {
            startEditingUrl(true, true);
        } else if (mVoiceButton == v) {
            mUiController.startVoiceRecognizer();
        } else if (mStopButton == v) {
            stopOrRefresh();
        } else if (mClearButton == v) {
            clearOrClose();
            mUrlInput.setText("");
        }
    }

    private static boolean urlHasSitePrefs(String url) {
        if (TextUtils.isEmpty(url)) {
            return false;
        }

        for (int i = 0; i < noSitePrefs.length; i++) {
            if (url.startsWith(noSitePrefs[i])) {
                return false;
            }
        }
        return true;
    }

    private void stopOrRefresh() {
        if (mUiController == null) return;
        if (mTitleBar.isInLoad()) {
            mUiController.stopLoading();
        } else {
            if (mUiController.getCurrentTopWebView() != null) {
                stopEditingUrl();
                mUiController.getCurrentTopWebView().reload();
            }
        }
    }

    private void clearOrClose() {
        if (TextUtils.isEmpty(mUrlInput.getText())) {
            // close
            mUrlInput.clearFocus();
        } else {
            // clear
            mUrlInput.setText("");
        }
    }

    void showMenu(View anchor) {
        Activity activity = mUiController.getActivity();
        if (mPopupMenu == null) {
            mPopupMenu = new PopupMenu(getContext(), anchor);
            mPopupMenu.setOnMenuItemClickListener(this);
            mPopupMenu.setOnDismissListener(this);
            if (!activity.onCreateOptionsMenu(mPopupMenu.getMenu())) {
                mPopupMenu = null;
                return;
            }
        }
        Menu menu = mPopupMenu.getMenu();

        if (mUiController instanceof Controller) {
            Controller controller = (Controller) mUiController;
            if (controller.onPrepareOptionsMenu(menu)) {
                mOverflowMenuShowing = true;
            }
        }
    }

    @Override
    public void onFocusChange(View view, boolean hasFocus) {
        // if losing focus and not in touch mode, leave as is
        if (hasFocus || view.isInTouchMode() || mUrlInput.needsUpdate()) {
            setFocusState(hasFocus);
        }
        if (hasFocus) {
            Engine.warmUpChildProcessAsync(mUiController.getActivity().getApplicationContext());
            mBaseUi.showTitleBar();
            if (!BrowserSettings.getInstance().isPowerSaveModeEnabled()) {
                //Notify about anticipated network activity
                NetworkServices.hintUpcomingUserActivity();
            }
        } else if (!mUrlInput.needsUpdate()) {
            mUrlInput.dismissDropDown();
            mUrlInput.hideIME();
            if (mUrlInput.getText().length() == 0) {
                Tab currentTab = mUiController.getTabControl().getCurrentTab();
                if (currentTab != null) {
                    setDisplayTitle(currentTab.getUrl());
                }
            }
        }
        mUrlInput.clearNeedsUpdate();
    }

    protected void setFocusState(boolean focus) {
    }

    public boolean isEditingUrl() {
        return mUrlInput.hasFocus();
    }

    void stopEditingUrl() {
        WebView currentTopWebView = mUiController.getCurrentTopWebView();
        if (currentTopWebView != null) {
            currentTopWebView.requestFocus();
        }
    }

    void setDisplayTitle(String title) {
        if (!isEditingUrl()) {
            if (!title.equals(mUrlInput.getText().toString())) {
                mUrlInput.setText(title, false);
            }
        }
    }

    void setIncognitoMode(boolean incognito) {
        mUrlInput.setIncognitoMode(incognito);
    }

    void clearCompletions() {
        mUrlInput.dismissDropDown();
    }

 // UrlInputListener implementation

    /**
     * callback from suggestion dropdown
     * user selected a suggestion
     */
    @Override
    public void onAction(String text, String extra, String source) {
        stopEditingUrl();
        if (UrlInputView.TYPED.equals(source)) {
            String url = null;
            boolean wap2estore = BrowserConfig.getInstance(getContext())
                    .hasFeature(BrowserConfig.Feature.WAP2ESTORE);
            if ((wap2estore && isEstoreTypeUrl(text)) || isRtspTypeUrl(text)
                || isMakeCallTypeUrl(text)) {
                url = text;
            } else {
                url = UrlUtils.smartUrlFilter(text, false);
            }

            Tab t = mBaseUi.getActiveTab();
            // Only shortcut javascript URIs for now, as there is special
            // logic in UrlHandler for other schemas
            if (url != null && t != null && url.startsWith("javascript:")) {
                mUiController.loadUrl(t, url);
                setDisplayTitle(text);
                return;
            }

            // add for carrier wap2estore feature
            if (url != null && t != null && wap2estore && isEstoreTypeUrl(url)) {
                if (handleEstoreTypeUrl(url)) {
                    setDisplayTitle(text);
                    return;
                }
            }
            // add for rtsp scheme feature
            if (url != null && t != null && isRtspTypeUrl(url)) {
                if (handleRtspTypeUrl(url)) {
                    setDisplayTitle(text);
                    return;
                }
            }
            // add for "wtai://wp/mc;" scheme feature
            if (url != null && t != null && isMakeCallTypeUrl(url)) {
                if (handleMakeCallTypeUrl(url)) {
                    return;
                }
            }
        }
        Intent i = new Intent();
        String action = Intent.ACTION_SEARCH;
        i.setAction(action);
        i.putExtra(SearchManager.QUERY, text);
        if (extra != null) {
            i.putExtra(SearchManager.EXTRA_DATA_KEY, extra);
        }
        if (source != null) {
            Bundle appData = new Bundle();
            appData.putString("source", source);
            i.putExtra("source", appData);
        }
        mUiController.handleNewIntent(i);
        setDisplayTitle(text);
    }

    private boolean isMakeCallTypeUrl(String url) {
        String utf8Url = null;
        try {
            utf8Url = new String(url.getBytes("UTF-8"), "UTF-8");
        } catch (UnsupportedEncodingException e) {
            Log.e(TAG, "err " + e);
        }
        if (utf8Url != null && utf8Url.startsWith(UrlHandler.SCHEME_WTAI_MC)) {
            return true;
        }
        return false;
    }

    private boolean handleMakeCallTypeUrl(String url) {
        // wtai://wp/mc;number
        // number=string(phone-number)
        if (url.startsWith(UrlHandler.SCHEME_WTAI_MC)) {
            Intent intent = new Intent(Intent.ACTION_VIEW,
                    Uri.parse(WebView.SCHEME_TEL +
                    url.substring(UrlHandler.SCHEME_WTAI_MC.length())));
            getContext().startActivity(intent);
            // before leaving BrowserActivity, close the empty child tab.
            // If a new tab is created through JavaScript open to load this
            // url, we would like to close it as we will load this url in a
            // different Activity.
            Tab current = mUiController.getCurrentTab();
            if (current != null
                    && current.getWebView().copyBackForwardList().getSize() == 0) {
                mUiController.closeCurrentTab();
            }
            return true;
        }
        return false;
    }

    private boolean isEstoreTypeUrl(String url) {
        if (url != null && url.startsWith("estore:")) {
            return true;
        }
        return false;
    }

    private boolean handleEstoreTypeUrl(String url) {
        if (url.getBytes().length > 256) {
            Toast.makeText(getContext(), R.string.estore_url_warning, Toast.LENGTH_LONG).show();
            return false;
        }

        Intent intent;
        // perform generic parsing of the URI to turn it into an Intent.
        try {
            intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
        } catch (URISyntaxException ex) {
            Log.w("Browser", "Bad URI " + url + ": " + ex.getMessage());
            return false;
        }

        try {
            getContext().startActivity(intent);
        } catch (ActivityNotFoundException ex) {
            String downloadUrl = getContext().getResources().getString(R.string.estore_homepage);
            mUiController.loadUrl(mBaseUi.getActiveTab(), downloadUrl);
            Toast.makeText(getContext(), R.string.download_estore_app, Toast.LENGTH_LONG).show();
        }

        return true;
    }

    private boolean isRtspTypeUrl(String url) {
        String utf8Url = null;
        try {
            utf8Url = new String(url.getBytes("UTF-8"), "UTF-8");
        } catch (UnsupportedEncodingException e) {
            Log.e(TAG, "err " + e);
        }
        if (utf8Url != null && utf8Url.startsWith("rtsp://")) {
            return true;
        }
        return false;
    }

    private boolean handleRtspTypeUrl(String url) {
        Intent intent;
        // perform generic parsing of the URI to turn it into an Intent.
        try {
            intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
        } catch (URISyntaxException ex) {
            Log.w("Browser", "Bad URI " + url + ": " + ex.getMessage());
            return false;
        }

        try {
            getContext().startActivity(intent);
        } catch (ActivityNotFoundException ex) {
            Log.w("Browser", "No resolveActivity " + url);
            return false;
        }
        return true;
    }

    @Override
    public void onDismiss() {
        final Tab currentTab = mBaseUi.getActiveTab();
        mBaseUi.hideTitleBar();
        post(new Runnable() {
            public void run() {
                clearFocus();
                if (currentTab != null) {
                    setDisplayTitle(currentTab.getUrl());
                }
            }
        });
    }

    /**
     * callback from the suggestion dropdown
     * copy text to input field and stay in edit mode
     */
    @Override
    public void onCopySuggestion(String text) {
        mUrlInput.setText(text, true);
        if (text != null) {
            mUrlInput.setSelection(text.length());
        }
    }

    public void setCurrentUrlIsBookmark(boolean isBookmark) {
    }

    @Override
    public boolean dispatchKeyEventPreIme(KeyEvent evt) {
        if (evt.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            // catch back key in order to do slightly more cleanup than usual
            stopEditingUrl();
        }
        return super.dispatchKeyEventPreIme(evt);
    }

    /**
     * called from the Ui when the user wants to edit
     * @param clearInput clear the input field
     */
    void startEditingUrl(boolean clearInput, boolean forceIME) {
        // editing takes preference of progress
        setVisibility(View.VISIBLE);
        if (!mUrlInput.hasFocus()) {
            mUrlInput.requestFocus();
        }
        if (clearInput) {
            mUrlInput.setText("");
        }
        if (forceIME) {
            mUrlInput.showIME();
        }
    }

    public void onProgressStarted() {
        mFaviconTile.setBadgeBlockedObjectsCount(0);
        mFaviconTile.setTrustLevel(SiteTileView.TRUST_UNKNOWN);
        mFaviconTile.setBadgeHasCertIssues(false);
        mFaviconTile.replaceFavicon(mDefaultFavicon);
        mSecurityState = Tab.SecurityState.SECURITY_STATE_NOT_SECURE;
        mHandler.removeMessages(WEBREFINER_COUNTER_MSG);
        mHandler.sendEmptyMessageDelayed(WEBREFINER_COUNTER_MSG,
                WEBREFINER_COUNTER_MSG_DELAY);
        mStopButton.setImageResource(R.drawable.ic_action_stop);
        mStopButton.setContentDescription(getResources().
                getString(R.string.accessibility_button_stop));
    }

    public void onProgressStopped() {
        if (!isEditingUrl()) {
            mFaviconTile.setVisibility(View.VISIBLE);
        }
        mStopButton.setImageResource(R.drawable.ic_action_reload);
        mStopButton.setContentDescription(getResources().
                getString(R.string.accessibility_button_refresh));
    }

    public void onTabDataChanged(Tab tab) {
    }

    public void onVoiceResult(String s) {
        startEditingUrl(true, true);
        onCopySuggestion(s);
    }

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) { }

    @Override
    public void afterTextChanged(Editable s) { }

    @Override
    public void onStateChanged(int state) {
        mVoiceButton.setVisibility(View.GONE);
        switch(state) {
            case STATE_NORMAL:
                mFaviconTile.setVisibility(View.VISIBLE);
                mMagnify.setVisibility(View.GONE);
                mClearButton.setVisibility(View.GONE);
                mMore.setVisibility(View.VISIBLE);
                if (mUiController != null) {
                    Tab currentTab = mUiController.getCurrentTab();
                    if (currentTab != null){
                        if (TextUtils.isEmpty(currentTab.getUrl())) {
                            mFaviconTile.setVisibility(View.GONE);
                            mMagnify.setVisibility(View.VISIBLE);
                        }
                    }
                    mUiController.setWindowDimming(0.0f);
                }

                break;
            case STATE_HIGHLIGHTED:
                mFaviconTile.setVisibility(View.GONE);
                mMagnify.setVisibility(View.GONE);
                mClearButton.setVisibility(View.GONE);
                mMore.setVisibility(View.GONE);
                if (mUiController != null) {
                    mUiController.setWindowDimming(0.75f);
                    if (mUiController.supportsVoice()) {
                        mVoiceButton.setVisibility(View.VISIBLE);
                    }
                }
                break;
            case STATE_EDITED:
                mFaviconTile.setVisibility(View.GONE);
                mMagnify.setVisibility(View.VISIBLE);
                mClearButton.setVisibility(View.VISIBLE);
                mMore.setVisibility(View.GONE);
                break;
        }
    }

    public boolean isMenuShowing() {
        return mOverflowMenuShowing;
    }


    @Override
    public void onDismiss(PopupMenu popupMenu) {
        if (popupMenu == mPopupMenu) {
            onMenuHidden();
        }
    }

    private void onMenuHidden() {
        mOverflowMenuShowing = false;
    }


    @Override
    public boolean onMenuItemClick(MenuItem item) {
        return mUiController.onOptionsItemSelected(item);
    }
}
