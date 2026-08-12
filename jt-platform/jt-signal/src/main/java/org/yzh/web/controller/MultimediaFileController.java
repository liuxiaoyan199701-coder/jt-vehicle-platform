package org.yzh.web.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.yzh.web.service.FileService;

@RestController
@RequestMapping("/files/multimedia")
public class MultimediaFileController {
    private final FileService fileService;

    public MultimediaFileController(FileService fileService) {
        this.fileService = fileService;
    }

    @GetMapping("/{deviceId}/{fileName:.+}")
    public ResponseEntity<Resource> download(
            @PathVariable String deviceId,
            @PathVariable String fileName) {
        final Path path;
        try {
            path = fileService.resolveMediaFile(deviceId, fileName)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid multimedia file path", error);
        }

        try {
            MediaType contentType = MediaTypeFactory.getMediaType(path.getFileName().toString())
                    .orElse(MediaType.APPLICATION_OCTET_STREAM);
            ContentDisposition disposition = ContentDisposition.attachment()
                    .filename(path.getFileName().toString(), StandardCharsets.UTF_8)
                    .build();
            return ResponseEntity.ok()
                    .contentType(contentType)
                    .contentLength(Files.size(path))
                    .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                    .body(new FileSystemResource(path));
        } catch (IOException error) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Multimedia file is unavailable", error);
        }
    }
}
