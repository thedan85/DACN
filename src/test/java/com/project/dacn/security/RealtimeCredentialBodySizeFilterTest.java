package com.project.dacn.security;

import com.project.dacn.common.exception.GlobalExceptionHandler;
import com.project.dacn.common.response.ApiResponse;
import com.project.dacn.dto.request.realtime.RealtimeCredentialRequest;
import com.project.dacn.integration.realtime.RealtimeRequestValidationException;
import jakarta.servlet.ServletInputStream;
import jakarta.validation.Valid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.DelegatingServletInputStream;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RealtimeCredentialBodySizeFilterTest {

    private static final String PATH = "/api/v1/realtime/credentials";
    private static final String JSON = "{\"productIds\":[\"tetra\"]}";

    private CredentialController controller;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        controller = new CredentialController();
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new RealtimeCredentialBodySizeFilter(JsonMapper.builder().build()))
                .build();
    }

    @Test
    void bodyAt32768BytesReachesMvcWithCachedBytes() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.CONTENT_LENGTH, 32768).content(paddedBody(32768)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0]").value("tetra"));

        assertThat(controller.calls).isEqualTo(1);
    }

    @Test
    void bodyAt32769BytesReturnsBounded413WithoutCallingController() throws Exception {
        var result = mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.CONTENT_LENGTH, 32769).content(paddedBody(32769)))
                .andExpect(status().is(413))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(413))
                .andExpect(jsonPath("$.message").value("request body too large"))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).hasSizeLessThan(200).doesNotContain("tetra");
        assertThat(controller.calls).isZero();
    }

    @ParameterizedTest
    @CsvSource({"-1, false", "-1, true", "0, false", "1, false", "32768, false"})
    void absentChunkedOrFalseContentLengthCannotBypassBoundedReads(int claimedLength, boolean chunked)
            throws Exception {
        var stream = new CountingInputStream(paddedBody(65536));

        mvc.perform(servletContext -> {
            var req = new MockHttpServletRequest(servletContext) {
                @Override
                public int getContentLength() {
                    return claimedLength;
                }

                @Override
                public long getContentLengthLong() {
                    return claimedLength;
                }

                @Override
                public ServletInputStream getInputStream() {
                    return stream;
                }
            };
            req.setMethod("POST");
            req.setRequestURI(PATH);
            req.setContentType(MediaType.APPLICATION_JSON_VALUE);
            if (claimedLength >= 0) {
                req.addHeader(HttpHeaders.CONTENT_LENGTH, claimedLength);
            }
            if (chunked) {
                req.addHeader(HttpHeaders.TRANSFER_ENCODING, "chunked");
            }
            return req;
        }).andExpect(status().is(413)).andExpect(jsonPath("$.code").value(413));

        assertThat(stream.bytesRead).isEqualTo(32769);
        assertThat(controller.calls).isZero();
    }

    @ParameterizedTest
    @CsvSource({"-1, false", "-1, true", "0, false", "1, false", "32768, false"})
    void bodyWithinLimitStillReachesMvcWithUntrustedLength(int claimedLength, boolean chunked) throws Exception {
        var stream = new CountingInputStream(paddedBody(32768));

        mvc.perform(servletContext -> {
            var req = new MockHttpServletRequest(servletContext) {
                @Override
                public int getContentLength() {
                    return claimedLength;
                }

                @Override
                public long getContentLengthLong() {
                    return claimedLength;
                }

                @Override
                public ServletInputStream getInputStream() {
                    return stream;
                }
            };
            req.setMethod("POST");
            req.setRequestURI(PATH);
            req.setContentType(MediaType.APPLICATION_JSON_VALUE);
            if (claimedLength >= 0) {
                req.addHeader(HttpHeaders.CONTENT_LENGTH, claimedLength);
            }
            if (chunked) {
                req.addHeader(HttpHeaders.TRANSFER_ENCODING, "chunked");
            }
            return req;
        }).andExpect(status().isOk()).andExpect(jsonPath("$.data[0]").value("tetra"));

        assertThat(stream.bytesRead).isEqualTo(32768);
        assertThat(controller.calls).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({"GET, /api/v1/realtime/credentials", "POST, /api/v1/other",
            "POST, /api/v1/realtime/credentials/other"})
    void otherMethodsAndPathsAreNotLimited(String method, String path) throws Exception {
        mvc.perform(request(HttpMethod.valueOf(method), path).contentType(MediaType.APPLICATION_JSON)
                        .content(paddedBody(32769)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(32769));

        assertThat(controller.calls).isEqualTo(1);
    }

    @Test
    void contextPathDoesNotBypassCeiling() throws Exception {
        mvc.perform(post("/shop" + PATH).contextPath("/shop").contentType(MediaType.APPLICATION_JSON)
                        .content(paddedBody(32769)))
                .andExpect(status().is(413))
                .andExpect(jsonPath("$.code").value(413));

        assertThat(controller.calls).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/realtime/cre%64entials", "/api/v1/realtime/credentials;param=value"})
    void equivalentMvcPathsCannotBypassCeiling(String path) throws Exception {
        mvc.perform(post(URI.create(path)).contentType(MediaType.APPLICATION_JSON).content(paddedBody(32769)))
                .andExpect(status().is(413))
                .andExpect(jsonPath("$.code").value(413));

        assertThat(controller.calls).isZero();
    }

    @Test
    void ceilingCountsUtf8BytesNotCharacters() throws Exception {
        String body = "{\"productIds\":[\"" + "é".repeat(16384) + "\"]}";

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().is(413))
                .andExpect(jsonPath("$.code").value(413));

        assertThat(controller.calls).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"productIds\":null}", "{\"productIds\":[\" \"]}",
            "{\"productIds\":[null]}"})
    void missingNullOrBlankIdsReturn400(String body) throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        assertThat(controller.calls).isZero();
    }

    @Test
    void overlongIdReturns400WithoutEchoingRejectedValue() throws Exception {
        String id = "private-input-" + "x".repeat(256);
        var result = mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productIds\":[\"" + id + "\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain(id).hasSizeLessThan(200);
        assertThat(controller.calls).isZero();
    }

    @Test
    void serviceValidationReturnsFixed400WithoutExceptionDetails() throws Exception {
        controller.failure = new RealtimeRequestValidationException() {
            @Override
            public String getMessage() {
                return "private-rejected-product-" + "x".repeat(1024);
            }
        };

        var result = mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("invalid realtime credential request"))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .hasSizeLessThan(200).doesNotContain("private-rejected-product");
    }

    private static byte[] paddedBody(int bytes) {
        return (JSON + " ".repeat(bytes - JSON.length())).getBytes(StandardCharsets.UTF_8);
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

    @RestController
    static class CredentialController {
        private int calls;
        private RealtimeRequestValidationException failure;

        @PostMapping(PATH)
        ApiResponse<List<String>> credentials(@Valid @RequestBody RealtimeCredentialRequest body) {
            calls++;
            if (failure != null) {
                throw failure;
            }
            return ApiResponse.ok(body.productIds());
        }

        @GetMapping(PATH)
        ApiResponse<Integer> getCredentials(@RequestBody byte[] body) {
            calls++;
            return ApiResponse.ok(body.length);
        }

        @RequestMapping({"/api/v1/other", "/api/v1/realtime/credentials/other"})
        ApiResponse<Integer> other(@RequestBody byte[] body) {
            calls++;
            return ApiResponse.ok(body.length);
        }
    }
}
