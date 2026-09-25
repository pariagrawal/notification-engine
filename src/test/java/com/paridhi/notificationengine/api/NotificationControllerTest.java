package com.paridhi.notificationengine.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.paridhi.notificationengine.domain.Channel;
import com.paridhi.notificationengine.domain.Notification;
import com.paridhi.notificationengine.domain.NotificationStatus;
import com.paridhi.notificationengine.exception.InvalidRequestException;
import com.paridhi.notificationengine.exception.NotFoundException;
import com.paridhi.notificationengine.exception.RateLimitExceededException;
import com.paridhi.notificationengine.service.NotificationIngestService;
import com.paridhi.notificationengine.service.NotificationQueryService;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(NotificationController.class)
class NotificationControllerTest {

    private static final String VALID_BODY = """
            {
              "userId": "user-1",
              "channel": "EMAIL",
              "templateCode": "welcome",
              "data": {"firstName": "Ada"},
              "recipient": "ada@example.com"
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NotificationIngestService ingestService;

    @MockitoBean
    private NotificationQueryService queryService;

    private Notification notification(UUID id, NotificationStatus status) {
        return Notification.builder()
                .id(id)
                .userId("user-1")
                .channel(Channel.EMAIL)
                .templateCode("welcome")
                .recipient("ada@example.com")
                .subject("Welcome aboard, Ada!")
                .body("Hi Ada")
                .status(status)
                .attempts(0)
                .createdAt(Instant.parse("2026-03-10T14:00:00Z"))
                .updatedAt(Instant.parse("2026-03-10T14:00:00Z"))
                .build();
    }

    @Test
    void acceptsAValidRequestWith202() throws Exception {
        UUID id = UUID.randomUUID();
        when(ingestService.ingest(any(), isNull()))
                .thenReturn(new NotificationIngestService.IngestResult(
                        notification(id, NotificationStatus.QUEUED), false));

        mockMvc.perform(post("/api/v1/notifications").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.duplicate").value(false));
    }

    @Test
    void answersARepeatedIdempotencyKeyWith200SoTheCallerKnowsNothingWasCreated() throws Exception {
        when(ingestService.ingest(any(), eq("key-1")))
                .thenReturn(new NotificationIngestService.IngestResult(
                        notification(UUID.randomUUID(), NotificationStatus.SENT), true));

        mockMvc.perform(post("/api/v1/notifications")
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true));
    }

    @Test
    void passesTheIdempotencyKeyHeaderThrough() throws Exception {
        when(ingestService.ingest(any(), any()))
                .thenReturn(new NotificationIngestService.IngestResult(
                        notification(UUID.randomUUID(), NotificationStatus.QUEUED), false));

        mockMvc.perform(post("/api/v1/notifications")
                .header("Idempotency-Key", "order-42")
                .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY));

        verify(ingestService).ingest(any(), eq("order-42"));
    }

    @Test
    void rejectsARequestMissingRequiredFields() throws Exception {
        mockMvc.perform(post("/api/v1/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel\": \"EMAIL\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.userId").exists())
                .andExpect(jsonPath("$.details.templateCode").exists());
    }

    @Test
    void rejectsAnUnknownChannel() throws Exception {
        mockMvc.perform(post("/api/v1/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"u\",\"channel\":\"CARRIER_PIGEON\",\"templateCode\":\"welcome\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void answersAThrottledCallerWith429AndARetryAfterHeader() throws Exception {
        when(ingestService.ingest(any(), any()))
                .thenThrow(new RateLimitExceededException("user user-1 exceeded 5 SMS notifications per window",
                        5, Duration.ofSeconds(42)));

        mockMvc.perform(post("/api/v1/notifications").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "42"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.details.limit").value("5"));
    }

    @Test
    void neverReturnsARetryAfterOfZero() throws Exception {
        // A sub-second window would round to 0 and send clients into a hot retry loop.
        when(ingestService.ingest(any(), any()))
                .thenThrow(new RateLimitExceededException("throttled", 5, Duration.ofMillis(200)));

        mockMvc.perform(post("/api/v1/notifications").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(header().string("Retry-After", "1"));
    }

    @Test
    void answersARequestWithNowhereToDeliverWith422() throws Exception {
        when(ingestService.ingest(any(), any()))
                .thenThrow(new InvalidRequestException("no EMAIL destination for user user-1"));

        mockMvc.perform(post("/api/v1/notifications").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("UNPROCESSABLE"));
    }

    @Test
    void returnsAStoredNotificationById() throws Exception {
        UUID id = UUID.randomUUID();
        when(queryService.require(id)).thenReturn(notification(id, NotificationStatus.SENT));

        mockMvc.perform(get("/api/v1/notifications/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SENT"))
                .andExpect(jsonPath("$.subject").value("Welcome aboard, Ada!"));
    }

    @Test
    void answers404ForAnUnknownNotification() throws Exception {
        UUID id = UUID.randomUUID();
        when(queryService.require(id)).thenThrow(new NotFoundException("no notification with id " + id));

        mockMvc.perform(get("/api/v1/notifications/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void searchesWithTheSuppliedFiltersAndPaging() throws Exception {
        when(queryService.search(any(), any(), any(), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(
                        java.util.List.of(notification(UUID.randomUUID(), NotificationStatus.DEAD_LETTERED))));

        mockMvc.perform(get("/api/v1/notifications")
                        .param("userId", "user-1")
                        .param("channel", "EMAIL")
                        .param("status", "DEAD_LETTERED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].status").value("DEAD_LETTERED"))
                .andExpect(jsonPath("$.totalElements").value(1));

        verify(queryService).search(eq("user-1"), eq(Channel.EMAIL), eq(NotificationStatus.DEAD_LETTERED), any());
    }

    @Test
    void capsThePageSizeSoOneCallCannotDrainTheTable() throws Exception {
        when(queryService.search(any(), any(), any(), any()))
                .thenReturn(org.springframework.data.domain.Page.empty());

        mockMvc.perform(get("/api/v1/notifications").param("size", "100000"))
                .andExpect(status().isOk());

        verify(queryService).search(isNull(), isNull(), isNull(),
                org.mockito.ArgumentMatchers.argThat(page -> page.getPageSize() == 200));
    }

    @Test
    void rejectsAnOverlongIdempotencyKeyWith400() throws Exception {
        mockMvc.perform(post("/api/v1/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "k".repeat(65))
                        .content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void answersAnUnsupportedContentTypeWith415RatherThan500() throws Exception {
        mockMvc.perform(post("/api/v1/notifications").contentType(MediaType.TEXT_PLAIN).content("hello"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void answersAWrongHttpMethodWith405RatherThan500() throws Exception {
        mockMvc.perform(delete("/api/v1/notifications"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().exists("Allow"));
    }
}
