package com.project.dacn.security;

import com.project.dacn.dto.response.catalog.StockSnapshotResponse;
import com.project.dacn.service.catalog.StockSnapshotService;
import jakarta.servlet.ServletInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.DelegatingServletInputStream;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real security chain and body filter behavior for the public stock snapshot route. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StockSnapshotSecurityTest {

    private static final String PATH = "/api/v1/stock/snapshots";
    private static final String BODY = "{\"productIds\":[\"PRD_1\"],\"variantIds\":[\"VAR_1\"]}";

    @MockitoBean
    private StockSnapshotService service;

    @Autowired
    private MockMvc mvc;

    @Test
    void anonymousSnapshotIsPublicAndNoStore() throws Exception {
        when(service.snapshots(any()))
                .thenReturn(new StockSnapshotResponse(List.of(), List.of()));

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.products").isEmpty())
                .andExpect(jsonPath("$.data.variants").isEmpty());

        verify(service).snapshots(any());
    }

    @Test
    void exactSixtyFourKilobytesReachesTheController() throws Exception {
        when(service.snapshots(any()))
                .thenReturn(new StockSnapshotResponse(List.of(), List.of()));

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(paddedBody(65536)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(service).snapshots(any());
    }

    @Test
    void sixtyFiveKilobytesAndOneByteReturns413BeforeTheController() throws Exception {
        var result = mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(paddedBody(65537)))
                .andExpect(status().is(413))
                .andExpect(jsonPath("$.code").value(413))
                .andExpect(jsonPath("$.message").value("request body too large"))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).hasSizeLessThan(200)
                .doesNotContain("PRD_1");
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @CsvSource({"-1, false", "0, false", "1, false", "-1, true", "65536, false"})
    void falseOrAbsentContentLengthCannotBypassTheCeiling(int claimedLength, boolean chunked)
            throws Exception {
        var request = post(PATH).contentType(MediaType.APPLICATION_JSON).content(paddedBody(65537));
        if (claimedLength >= 0) {
            request.header(HttpHeaders.CONTENT_LENGTH, claimedLength);
        }
        if (chunked) {
            request.header(HttpHeaders.TRANSFER_ENCODING, "chunked");
        }

        mvc.perform(request)
                .andExpect(status().is(413))
                .andExpect(jsonPath("$.code").value(413));

        verifyNoInteractions(service);
    }

    @Test
    void chunkedStreamIsReadToCapPlusOne() throws Exception {
        var stream = new CountingInputStream(paddedBody(65537));

        mvc.perform(servletContext -> {
            var request = new MockHttpServletRequest(servletContext) {
                @Override
                public int getContentLength() {
                    return -1;
                }

                @Override
                public long getContentLengthLong() {
                    return -1;
                }

                @Override
                public ServletInputStream getInputStream() {
                    return stream;
                }
            };
            request.setMethod("POST");
            request.setRequestURI(PATH);
            request.setContentType(MediaType.APPLICATION_JSON_VALUE);
            request.addHeader(HttpHeaders.TRANSFER_ENCODING, "chunked");
            return request;
        }).andExpect(status().is(413)).andExpect(jsonPath("$.code").value(413));

        assertThat(stream.bytesRead).isEqualTo(65537);
        verifyNoInteractions(service);
    }

    @Test
    void chunkedBodyAtTheLimitReachesTheController() throws Exception {
        when(service.snapshots(any()))
                .thenReturn(new StockSnapshotResponse(List.of(), List.of()));
        var stream = new CountingInputStream(paddedBody(65536));

        mvc.perform(servletContext -> {
            var request = new MockHttpServletRequest(servletContext) {
                @Override
                public int getContentLength() {
                    return -1;
                }

                @Override
                public long getContentLengthLong() {
                    return -1;
                }

                @Override
                public ServletInputStream getInputStream() {
                    return stream;
                }
            };
            request.setMethod("POST");
            request.setRequestURI(PATH);
            request.setContentType(MediaType.APPLICATION_JSON_VALUE);
            request.addHeader(HttpHeaders.TRANSFER_ENCODING, "chunked");
            return request;
        }).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));

        assertThat(stream.bytesRead).isEqualTo(65536);
        verify(service).snapshots(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/stoc%6B/snapshots", "/api/v1/stock/snapshots;v=1"})
    void encodedAndParameterizedPathsCannotBypassTheCeiling(String path) throws Exception {
        mvc.perform(post(URI.create(path)).contentType(MediaType.APPLICATION_JSON)
                        .content(paddedBody(65537)))
                .andExpect(status().is(413))
                .andExpect(jsonPath("$.code").value(413));

        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @CsvSource({
            "POST, /api/v1/stock/other",
            "POST, /api/v1/stock/snapshots/other",
            "GET, /api/v1/stock/snapshots",
            "PUT, /api/v1/stock/snapshots"
    })
    void onlyTheExactPostRouteIsPublic(String method, String path) throws Exception {
        mvc.perform(request(HttpMethod.valueOf(method), path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productIds\":[\"PRD_1\"],\"variantIds\":[]}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        verifyNoInteractions(service);
    }

    private static byte[] paddedBody(int bytes) {
        return (BODY + " ".repeat(bytes - BODY.length())).getBytes(StandardCharsets.UTF_8);
    }

    private static class CountingInputStream extends DelegatingServletInputStream {
        private final ByteArrayInputStream source;
        private int bytesRead;

        CountingInputStream(byte[] body) {
            this(new ByteArrayInputStream(body));
        }

        private CountingInputStream(ByteArrayInputStream source) {
            super(source);
            this.source = source;
        }

        @Override
        public int read() {
            int value = source.read();
            if (value != -1) {
                bytesRead++;
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            int count = source.read(buffer, offset, length);
            if (count > 0) {
                bytesRead += count;
            }
            return count;
        }
    }
}
