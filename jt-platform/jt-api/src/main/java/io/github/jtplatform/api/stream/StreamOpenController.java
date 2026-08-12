package io.github.jtplatform.api.stream;

import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/stream")
public final class StreamOpenController {
    private final StreamOpenService service;

    public StreamOpenController(StreamOpenService service) {
        this.service = service;
    }

    @PostMapping("/open")
    public OpenStreamResponse open(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @Valid @RequestBody OpenStreamRequest request) {
        return service.open(authorization, request);
    }
}
