package io.testomat.client;

import io.testomat.exception.CliException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

public class CliClient implements TestomatHttpClient {

    private static final String TEST_DATA_URL = "/api/test_data?api_key=";
    private static final String USER_AGENT = "Testomat-CLI/1.0";
    private static final String CONTENT_TYPE_JSON = "application/json";
    private static final String ACCEPT_JSON = "application/json";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration GET_REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration POST_REQUEST_TIMEOUT = Duration.ofMinutes(5);

    private static final int MAX_RETRIES = 3;
    private static final int RETRY_DELAY_MS = 1000;
    private static final int SUCCESS_STATUS_MIN = 200;
    private static final int SUCCESS_STATUS_MAX = 299;
    private static final int CLIENT_ERROR_MIN = 400;
    private static final int CLIENT_ERROR_MAX = 499;

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Override
    public String sendGetRequest(String apiKey, String serverUrl) {
        validateApiKey(apiKey);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(serverUrl + TEST_DATA_URL + apiKey))
                    .timeout(GET_REQUEST_TIMEOUT)
                    .header("Accept", ACCEPT_JSON)
                    .GET()
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request,
                    HttpResponse.BodyHandlers.ofString());

            validateGetResponse(response);
            return response.body();

        } catch (HttpTimeoutException e) {
            throw new CliException("Request timeout after "
                    + GET_REQUEST_TIMEOUT.getSeconds()
                    + " seconds: "
                    + e.getMessage(), e);
        } catch (IOException e) {
            throw new CliException("Network error occurred while sending request: "
                    + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CliException("Request was interrupted: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new CliException("Unexpected error occurred: " + e.getMessage(), e);
        }
    }

    @Override
    public void sendPostRequest(String url, String jsonBody) {
        int attempt = 1;
        Exception lastException = null;

        while (attempt <= MAX_RETRIES) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Content-Type", CONTENT_TYPE_JSON)
                        .header("User-Agent", USER_AGENT)
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                        .timeout(POST_REQUEST_TIMEOUT)
                        .build();

                HttpResponse<String> response = HTTP_CLIENT.send(request,
                        HttpResponse.BodyHandlers.ofString());

                if (isSuccessfulResponse(response)) {
                    return;
                }

                String errorMessage = formatPostHttpError(response);

                if (isClientError(response)) {
                    throw new CliException(errorMessage);
                }

                lastException = new CliException(errorMessage);

            } catch (ConnectException e) {
                lastException = new CliException("Cannot connect to testomat.io server. "
                        + "Please check your internet connection.", e);
                System.err.println("Connection error: " + e.getMessage());
            } catch (SocketTimeoutException e) {
                String msg = "Request timed out after "
                        + POST_REQUEST_TIMEOUT.getSeconds() + " seconds. Server might be busy.";
                lastException = new CliException(msg, e);
                System.err.println("Socket timeout: " + e.getMessage());
            } catch (HttpTimeoutException e) {
                lastException = new CliException("HTTP request timed out after "
                        + POST_REQUEST_TIMEOUT.getSeconds() + " seconds.", e);
                System.err.println("HTTP timeout: " + e.getMessage());
            } catch (IOException e) {
                lastException = new CliException("Network error: " + e.getMessage(), e);
                System.err.println("IO error: " + e.getClass().getSimpleName()
                        + ": " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CliException("Export interrupted", e);
            }

            if (attempt < MAX_RETRIES) {
                System.err.println("Attempt " + attempt + " failed, retrying in "
                        + (RETRY_DELAY_MS * attempt) + "ms...");
                try {
                    Thread.sleep(RETRY_DELAY_MS * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new CliException("Export interrupted", ie);
                }
            }

            attempt++;
        }

        String errorDetail = lastException != null
                ? lastException.getMessage() : "Unknown error";
        throw new CliException("Failed after " + MAX_RETRIES + " attempts: " + errorDetail,
                lastException);
    }

    private void validateApiKey(String apiKey) {
        if (apiKey == null || apiKey.trim().isEmpty() || !apiKey.startsWith("tstmt_")) {
            throw new IllegalArgumentException("API key cannot be null or empty"
                    + " and should start with 'tstmt_'");
        }
    }

    private void validateGetResponse(HttpResponse<String> response) {
        int statusCode = response.statusCode();

        if (statusCode == 200) {
            return;
        }

        String errorMessage = buildGetErrorMessage(statusCode);
        throw new CliException(errorMessage + ". Response: " + response.body());
    }

    private String buildGetErrorMessage(int statusCode) {
        switch (statusCode) {
            case 401:
                return "Unauthorized - invalid API key";
            case 403:
                return "Forbidden - access denied";
            case 404:
                return "Not found - check the endpoint URL";
            case 429:
                return "Too many requests - rate limit exceeded";
            case 500:
                return "Internal server error";
            case 502:
            case 503:
            case 504:
                return "Service temporarily unavailable";
            default:
                return "HTTP error " + statusCode;
        }
    }

    private boolean isSuccessfulResponse(HttpResponse<String> response) {
        int statusCode = response.statusCode();
        return statusCode >= SUCCESS_STATUS_MIN && statusCode <= SUCCESS_STATUS_MAX;
    }

    private boolean isClientError(HttpResponse<String> response) {
        int statusCode = response.statusCode();
        return statusCode >= CLIENT_ERROR_MIN && statusCode <= CLIENT_ERROR_MAX;
    }

    private String formatPostHttpError(HttpResponse<String> response) {
        String body = response.body();
        int statusCode = response.statusCode();

        switch (statusCode) {
            case 401:
                return "HTTP 401: Invalid API key. Please check your API key.";
            case 403:
                return "HTTP 403: Access denied. Please check your API key permissions.";
            case 404:
                return "HTTP 404: API endpoint not found. Please check the server URL.";
            case 422:
                return "HTTP 422: Invalid data format. "
                        + (body != null && !body.isEmpty() ? "Server response: " + body : "");
            case 429:
                return "HTTP 429: Rate limit exceeded. Please try again later.";
            case 500:
                return "HTTP 500: Server error. Please try again later.";
            case 503:
                return "HTTP 503: Service unavailable. Please try again later.";
            default:
                return "HTTP " + statusCode + ": "
                        + (body != null && !body.isEmpty() ? body : "Unknown error");
        }
    }
}
