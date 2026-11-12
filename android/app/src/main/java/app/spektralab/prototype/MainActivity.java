package app.spektralab.prototype;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.webkit.WebView;
import android.window.OnBackInvokedDispatcher;

public final class MainActivity extends Activity {
    private WebView webView;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(12, 11, 10));
        getWindow().setNavigationBarColor(Color.rgb(12, 11, 10));

        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(12, 11, 10));
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setAllowContentAccess(false);
        webView.getSettings().setAllowFileAccess(true);
        webView.getSettings().setAllowFileAccessFromFileURLs(false);
        webView.getSettings().setAllowUniversalAccessFromFileURLs(false);
        webView.loadUrl("file:///android_asset/index.html");
        setContentView(webView);
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
        }
    }

    @Override
    public void onBackPressed() {
        handleBack();
    }

    private void handleBack() {
        // The local app owns its view/modal stack; WebView history has only index.html.
        webView.evaluateJavascript(
            "typeof window.silvercamBack === 'function' && window.silvercamBack()",
            handled -> {
                if (!"true".equals(handled) && !isFinishing() && !isDestroyed()) {
                    finish();
                }
            });
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
        }
        super.onDestroy();
    }
}
