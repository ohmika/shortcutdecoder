package com.ohmika.shortcutdecoder;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;

public final class MainActivity extends Activity {
    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        webView.addJavascriptInterface(new ICloudBridge(), "AndroidBridge");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("file".equalsIgnoreCase(uri.getScheme())
                        && "android_asset".equals(uri.getAuthority())) {
                    return false;
                }
                if ("https".equalsIgnoreCase(uri.getScheme())) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, uri));
                    } catch (Exception ignored) {
                        // Leave the page in place if no browser can handle the link.
                    }
                    return true;
                }
                return true;
            }
        });

        setContentView(webView);
        webView.loadUrl("file:///android_asset/www/index.html");
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    public final class ICloudBridge {
        private static final int MAX_METADATA_BYTES = 2 * 1024 * 1024;
        private static final int MAX_SHORTCUT_BYTES = 48 * 1024 * 1024;
        private static final int MAX_REDIRECTS = 5;
        private final Pattern linkPattern = Pattern.compile(
                "shortcuts/(?:api/records/)?([0-9a-f-]{32,36})",
                Pattern.CASE_INSENSITIVE
        );

        @JavascriptInterface
        public String fetchICloud(String sharedLink) {
            JSONObject result = new JSONObject();
            try {
                Matcher matcher = linkPattern.matcher(sharedLink == null ? "" : sharedLink);
                if (!matcher.find()) {
                    throw new IllegalArgumentException(
                            "Geen geldige iCloud Shortcuts-link gevonden."
                    );
                }

                String recordId = matcher.group(1).replace("-", "").toLowerCase(Locale.ROOT);
                if (!recordId.matches("[0-9a-f]{32}")) {
                    throw new IllegalArgumentException("De iCloud-link bevat geen geldige shortcut-ID.");
                }

                String recordUrl = "https://www.icloud.com/shortcuts/api/records/" + recordId;
                byte[] metadataBytes = requestBytes(recordUrl, false, MAX_METADATA_BYTES);
                JSONObject metadata = new JSONObject(
                        new String(metadataBytes, StandardCharsets.UTF_8)
                );

                JSONObject fields = metadata.optJSONObject("fields");
                JSONObject shortcutField = fields == null ? null : fields.optJSONObject("shortcut");
                JSONObject shortcutValue = shortcutField == null ? null : shortcutField.optJSONObject("value");
                String downloadUrl = shortcutValue == null ? null : shortcutValue.optString("downloadURL", null);
                if (downloadUrl == null || downloadUrl.isEmpty()) {
                    throw new IllegalStateException(
                            "iCloud gaf geen downloadURL terug. De link kan privé/verwijderd zijn of Apple heeft de API gewijzigd."
                    );
                }

                JSONObject nameField = fields == null ? null : fields.optJSONObject("name");
                String shortcutName = nameField == null ? "shortcut" : nameField.optString("value", "shortcut");
                String safeName = sanitizeFileName(shortcutName);
                String downloadName = safeName + ".shortcut";
                String finalDownloadUrl = downloadUrl.replace(
                        "${f}",
                        Uri.encode(downloadName)
                );

                byte[] fileBytes = requestBytes(finalDownloadUrl, true, MAX_SHORTCUT_BYTES);
                result.put("ok", true);
                result.put("name", downloadName);
                result.put("data", Base64.encodeToString(fileBytes, Base64.NO_WRAP));
            } catch (Exception error) {
                try {
                    result.put("ok", false);
                    result.put("error", safeError(error));
                } catch (Exception ignored) {
                    return "{\"ok\":false,\"error\":\"Download mislukt.\"}";
                }
            }
            return result.toString();
        }

        private byte[] requestBytes(String startUrl, boolean allowAppleCdn, int maxBytes) throws Exception {
            String currentUrl = startUrl;
            for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
                URL url = new URL(currentUrl);
                validateHttpsHost(url, allowAppleCdn);

                HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(30000);
                connection.setRequestMethod("GET");
                connection.setRequestProperty("User-Agent",
                        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36");
                connection.setRequestProperty("Accept", "application/json, application/octet-stream, */*");
                connection.setRequestProperty("Referer", "https://www.icloud.com/shortcuts/");

                int status = connection.getResponseCode();
                if (status >= 300 && status < 400) {
                    String location = connection.getHeaderField("Location");
                    connection.disconnect();
                    if (location == null || location.isEmpty() || redirect == MAX_REDIRECTS) {
                        throw new IllegalStateException("Te veel of ongeldige iCloud-redirects.");
                    }
                    currentUrl = new URL(url, location).toString();
                    continue;
                }

                if (status < 200 || status >= 300) {
                    connection.disconnect();
                    throw new IllegalStateException("iCloud-download mislukt (HTTP " + status + ").");
                }

                try (InputStream input = connection.getInputStream()) {
                    return readLimited(input, maxBytes);
                } finally {
                    connection.disconnect();
                }
            }
            throw new IllegalStateException("Te veel iCloud-redirects.");
        }

        private void validateHttpsHost(URL url, boolean allowAppleCdn) throws Exception {
            if (!"https".equalsIgnoreCase(url.getProtocol())) {
                throw new SecurityException("Alleen beveiligde HTTPS-downloads zijn toegestaan.");
            }
            String host = url.getHost().toLowerCase(Locale.ROOT);
            boolean iCloudHost = host.equals("icloud.com") || host.endsWith(".icloud.com");
            boolean appleCdnHost = host.equals("icloud-content.com")
                    || host.endsWith(".icloud-content.com")
                    || host.equals("apple.com")
                    || host.endsWith(".apple.com");
            if (!iCloudHost && !(allowAppleCdn && appleCdnHost)) {
                throw new SecurityException("iCloud verwees naar een niet-toegestane downloadhost.");
            }
        }

        private byte[] readLimited(InputStream input, int maxBytes) throws Exception {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > maxBytes) {
                    throw new IllegalStateException("Het shortcut-bestand is groter dan de toegestane limiet.");
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }

        private String sanitizeFileName(String name) {
            if (name == null || name.trim().isEmpty()) return "shortcut";
            String safe = name.replaceAll("[\\\\/:*?\"<>|#%]", "-").trim();
            if (safe.length() > 100) safe = safe.substring(0, 100);
            return safe.isEmpty() ? "shortcut" : safe;
        }

        private String safeError(Exception error) {
            String message = error.getMessage();
            if (message == null || message.trim().isEmpty()) return "Download mislukt.";
            return message.length() > 240 ? message.substring(0, 240) : message;
        }
    }
}
