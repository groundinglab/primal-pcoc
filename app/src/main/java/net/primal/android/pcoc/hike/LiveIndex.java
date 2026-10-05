package net.primal.android.pcoc.hike;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Live index only. {@link #LIVE_INDEX_HOST} is the host. {@link #LIVE_INDEX_URL} is the URL.
 * Every GET sends {@link #USER_AGENT}. A missing user agent is a 403 and would mark a good object invalid.
 */
public final class LiveIndex implements IndexSource {

    public static final String LIVE_INDEX_URL = "https://index.pcoc.app";
    public static final String LIVE_INDEX_HOST = "index.pcoc.app";
    public static final String USER_AGENT = "pcoc-hike";
    public static final int READ_TIMEOUT_SECONDS = 30;

    private final okhttp3.Call.Factory http;

    public LiveIndex(okhttp3.Call.Factory http) {
        this.http = http;
    }

    public static OkHttpClient newClient() {
        return new OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build();
    }

    @Override
    public String read(long height) throws IndexUnreachable {
        String url = LIVE_INDEX_URL + "/index/" + Long.toUnsignedString(height);
        Request request = new Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .get()
            .build();
        try (Response response = http.newCall(request).execute()) {
            if (response.code() != 200 || response.priorResponse() != null || response.body() == null) {
                return null;
            }
            byte[] raw = response.body().bytes();
            if (raw.length >= 3
                && (raw[0] & 0xff) == 0xef
                && (raw[1] & 0xff) == 0xbb
                && (raw[2] & 0xff) == 0xbf) {
                return null;
            }
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
            return decoder.decode(ByteBuffer.wrap(raw)).toString();
        } catch (CharacterCodingException notUtf8) {
            return null;
        } catch (IOException failed) {
            throw new IndexUnreachable();
        }
    }
}
