package com.marmitt.application.spring.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import java.io.IOException;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void methodNotSupported_preservesOriginalStatus() throws Exception {
        // HttpRequestMethodNotSupportedException implements ErrorResponse (405) — must NOT become 500.
        mockMvc.perform(post("/probe/get-only"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.path").value("/probe/get-only"));
    }

    @Test
    void illegalArgument_mapsToBadRequest() throws Exception {
        mockMvc.perform(get("/probe/illegal"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("bad input"));
    }

    @Test
    void unexpectedException_mapsToInternalServerError() throws Exception {
        mockMvc.perform(get("/probe/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.message").value("Internal server error"));
    }

    /** T53 F4.12: cliente que fechou a conexao nao vira 500 nem ERROR; nao ha corpo a escrever. */
    @Test
    void clientDisconnected_writesNothing() throws Exception {
        mockMvc.perform(get("/probe/broken-pipe"))
                .andExpect(status().isOk())
                .andExpect(content().string(""));
        mockMvc.perform(get("/probe/not-usable"))
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }

    @RestController
    @RequestMapping("/probe")
    static class ProbeController {

        @GetMapping("/get-only")
        public String getOnly() {
            return "ok";
        }

        @GetMapping("/illegal")
        public String illegal() {
            throw new IllegalArgumentException("bad input");
        }

        @GetMapping("/broken-pipe")
        public String brokenPipe() throws IOException {
            throw new IOException("Broken pipe");
        }

        @GetMapping("/not-usable")
        public String notUsable() throws IOException {
            throw new AsyncRequestNotUsableException("ServletOutputStream failed to write: java.io.IOException: Broken pipe");
        }

        @GetMapping("/boom")
        public String boom() {
            throw new IllegalStateException("unexpected");
        }
    }
}
