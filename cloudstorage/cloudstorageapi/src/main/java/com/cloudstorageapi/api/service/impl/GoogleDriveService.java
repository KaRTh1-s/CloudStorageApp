package com.cloudstorageapi.api.service.impl;

import com.cloudstorageapi.api.config.CloudStorageConfig;
import com.cloudstorageapi.api.model.CloudStorageToken;
import com.cloudstorageapi.api.service.CloudStorageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;
import java.util.stream.Collectors;
import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Service
public class GoogleDriveService implements CloudStorageService {

    @Autowired
    private CloudStorageConfig config;

    private final RestTemplate restTemplate = new RestTemplate();

    private Map<String, CloudStorageToken> tokenStore = new HashMap<>();

    public List<String> listFiles(String accessToken) {
    String url = "https://www.googleapis.com/drive/v3/files";
    HttpHeaders headers = new HttpHeaders();
    headers.set("Authorization", "Bearer " + accessToken);

    HttpEntity<String> entity = new HttpEntity<>(headers);
    ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);

    List<Map<String, Object>> files = (List<Map<String, Object>>) response.getBody().get("files");
    return files.stream()
                .map(file -> (String) file.get("name"))
                .collect(Collectors.toList());
}

    @Override
    public CloudStorageToken connect(String authCode, String userId) throws Exception {
        CloudStorageToken token = exchangeAuthCode(authCode);
        token.setUserId(userId);
        token.setProvider("google");
        saveTokenForUser(userId, token); // Save the token
        return token;
    }

    @Override
    public CloudStorageToken refreshToken(CloudStorageToken token) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("client_id", config.getGoogle().getClientId());
        body.add("client_secret", config.getGoogle().getClientSecret());
        body.add("refresh_token", token.getRefreshToken());
        body.add("grant_type", "refresh_token");

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);
        TokenResponse response = restTemplate.postForObject(
                config.getGoogle().getTokenUri(),
                request,
                TokenResponse.class
        );

        if (response != null) {
            token.setAccessToken(response.getAccessToken());
            token.setExpiresAt(Instant.now().plusSeconds(response.getExpiresIn()));
            saveTokenForUser(token.getUserId(), token);
            return token;
        }
        throw new Exception("Failed to refresh token");
    }

    @Override
    public CloudStorageToken validateToken(CloudStorageToken token) throws Exception {
        if (token.isExpired()) {
            return refreshToken(token);
        }
        return token;
    }

    @Override
    public void disconnect(CloudStorageToken token) throws Exception {
        // Implement token revocation if needed
    }

    @Override
    public String uploadFile(MultipartFile file, String userId) throws Exception {
        // Validate and refresh token if needed
        CloudStorageToken token = validateToken(getTokenForUser(userId));

        // Prepare the upload request
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token.getAccessToken());
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(file.getBytes()) {
            @Override
            public String getFilename() {
                return file.getOriginalFilename();
            }
        });

        HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

        // Make the upload request to Google Drive API
        ResponseEntity<FileUploadResponse> response = restTemplate.exchange(
            "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart",
            HttpMethod.POST,
            requestEntity,
            FileUploadResponse.class
        );

        if (response.getBody() != null) {
            return response.getBody().getId();
        }
        throw new Exception("Failed to upload file");
    }

    @Override
    public Resource downloadFile(String fileId, String userId) throws Exception {
        // Validate and refresh token if needed
        CloudStorageToken token = validateToken(getTokenForUser(userId));

        // Prepare the download request
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token.getAccessToken());

        HttpEntity<?> requestEntity = new HttpEntity<>(headers);

        // Make the download request to Google Drive API
        ResponseEntity<byte[]> response = restTemplate.exchange(
            String.format("https://www.googleapis.com/drive/v3/files/%s?alt=media", fileId),
            HttpMethod.GET,
            requestEntity,
            byte[].class
        );

        if (response.getBody() != null) {
            return new ByteArrayResource(response.getBody());
        }
        throw new Exception("Failed to download file");
    }

    @Override
    public void deleteFile(String fileId, String userId) throws Exception {
        // Validate and refresh token if needed
        CloudStorageToken token = validateToken(getTokenForUser(userId));

        // Prepare the delete request
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token.getAccessToken());

        HttpEntity<?> requestEntity = new HttpEntity<>(headers);

        // Make the delete request to Google Drive API
        restTemplate.exchange(
            String.format("https://www.googleapis.com/drive/v3/files/%s", fileId),
            HttpMethod.DELETE,
            requestEntity,
            Void.class
        );
    }

    private CloudStorageToken getTokenForUser(String userId) {
        return tokenStore.get(userId);
    }

    private void saveTokenForUser(String userId, CloudStorageToken token) {
        tokenStore.put(userId, token);
    }

    private CloudStorageToken exchangeAuthCode(String authCode) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("code", authCode);
        body.add("client_id", config.getGoogle().getClientId());
        body.add("client_secret", config.getGoogle().getClientSecret());
        body.add("redirect_uri", config.getGoogle().getRedirectUri());
        body.add("grant_type", "authorization_code");

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);
        TokenResponse response = restTemplate.postForObject(
                config.getGoogle().getTokenUri(),
                request,
                TokenResponse.class
        );

        if (response != null) {
            CloudStorageToken token = new CloudStorageToken();
            token.setAccessToken(response.getAccessToken());
            token.setRefreshToken(response.getRefreshToken());
            token.setTokenType(response.getTokenType());
            token.setExpiresAt(Instant.now().plusSeconds(response.getExpiresIn()));
            return token;
        }
        throw new Exception("Failed to exchange authorization code");
    }

    private static class FileUploadResponse {
        private String id;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
    }

    private static class TokenResponse {
        private String accessToken;
        private String refreshToken;
        private String tokenType;
        private int expiresIn;

        public String getAccessToken() { return accessToken; }
        public void setAccessToken(String accessToken) { this.accessToken = accessToken; }
        public String getRefreshToken() { return refreshToken; }
        public void setRefreshToken(String refreshToken) { this.refreshToken = refreshToken; }
        public String getTokenType() { return tokenType; }
        public void setTokenType(String tokenType) { this.tokenType = tokenType; }
        public int getExpiresIn() { return expiresIn; }
        public void setExpiresIn(int expiresIn) { this.expiresIn = expiresIn; }
    }
}
