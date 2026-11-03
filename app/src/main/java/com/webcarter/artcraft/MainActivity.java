package com.webcarter.artcraft;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebSettings;
import android.widget.Toast;
import androidx.webkit.WebViewAssetLoader;
import java.io.OutputStream;

public class MainActivity extends Activity {
    private static final int OPEN_FILE = 1001;
    private static final int SAVE_FILE = 1002;
    private WebView webView;
    private WebViewAssetLoader assetLoader;
    private ValueCallback<Uri[]> fileCallback;
    private byte[] pendingBytes;
    private String pendingMime = "application/octet-stream";
    private String pendingName = "download";

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        assetLoader = new WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this)).build();
        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setSupportMultipleWindows(false);
        webView.addJavascriptInterface(new FileBridge(), "ArtCraftFiles");
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
                startActivityForResult(intent, OPEN_FILE);
                return true;
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String host = request.getUrl().getHost();
                return host != null && !host.equals("appassets.androidplatform.net");
            }
            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                installBlobDownloadHook();
            }
        });
        setContentView(webView);
        webView.loadUrl("https://appassets.androidplatform.net/assets/home.html");
    }

    private void installBlobDownloadHook() {
        String js = "(function(){if(window.__artcraftSaveHook)return;window.__artcraftSaveHook=1;" +
          "document.addEventListener('click',function(e){var a=e.target.closest&&e.target.closest('a');" +
          "if(!a||!a.href||!a.href.startsWith('blob:'))return;e.preventDefault();e.stopPropagation();" +
          "var name=a.download||'ArtCraft-export';fetch(a.href).then(r=>r.blob()).then(b=>{" +
          "var reader=new FileReader();reader.onload=()=>ArtCraftFiles.saveBlob(name,b.type||'application/octet-stream',reader.result.split(',')[1]);reader.readAsDataURL(b);" +
          "}).catch(err=>ArtCraftFiles.reportError(String(err)));},true);})();";
        webView.evaluateJavascript(js, null);
    }

    private class FileBridge {
        @JavascriptInterface public void saveBlob(String name, String mime, String base64) {
            try {
                pendingName = (name == null || name.isEmpty()) ? "ArtCraft-export" : name;
                pendingMime = (mime == null || mime.isEmpty()) ? "application/octet-stream" : mime;
                pendingBytes = Base64.decode(base64, Base64.DEFAULT);
                runOnUiThread(() -> {
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType(pendingMime);
                    intent.putExtra(Intent.EXTRA_TITLE, pendingName);
                    startActivityForResult(intent, SAVE_FILE);
                });
            } catch (Exception ex) { reportError(ex.toString()); }
        }
        @JavascriptInterface public void reportError(String message) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show());
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == OPEN_FILE) {
            if (fileCallback != null) {
                Uri[] results = null;
                if (resultCode == RESULT_OK && data != null) {
                    if (data.getClipData() != null) {
                        int n = data.getClipData().getItemCount(); results = new Uri[n];
                        for (int i=0;i<n;i++) results[i] = data.getClipData().getItemAt(i).getUri();
                    } else if (data.getData() != null) results = new Uri[]{data.getData()};
                }
                fileCallback.onReceiveValue(results); fileCallback = null;
            }
        } else if (requestCode == SAVE_FILE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            try (OutputStream out = getContentResolver().openOutputStream(data.getData())) {
                if (out != null && pendingBytes != null) out.write(pendingBytes);
                Toast.makeText(this, "File saved", Toast.LENGTH_SHORT).show();
            } catch (Exception ex) {
                Toast.makeText(this, "Could not save: " + ex.getMessage(), Toast.LENGTH_LONG).show();
            } finally { pendingBytes = null; }
        }
    }

    @Override public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
