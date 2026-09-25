package com.paridhi.notificationengine.api;

import com.paridhi.notificationengine.api.dto.DeliveryAttemptResponse;
import com.paridhi.notificationengine.api.dto.NotificationResponse;
import com.paridhi.notificationengine.api.dto.PageResponse;
import com.paridhi.notificationengine.api.dto.SendNotificationRequest;
import com.paridhi.notificationengine.domain.Channel;
import com.paridhi.notificationengine.domain.NotificationStatus;
import com.paridhi.notificationengine.service.NotificationIngestService;
import com.paridhi.notificationengine.service.NotificationQueryService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private static final int MAX_PAGE_SIZE = 200;

    private final NotificationIngestService ingestService;
    private final NotificationQueryService queryService;

    public NotificationController(NotificationIngestService ingestService,
                                  NotificationQueryService queryService) {
        this.ingestService = ingestService;
        this.queryService = queryService;
    }

    /**
     * Accepts a notification for delivery.
     *
     * <p>Returns 202 for a newly accepted request and 200 when the {@code Idempotency-Key}
     * matched an earlier one, so a caller retrying after a timeout can tell whether it
     * created anything.
     */
    @PostMapping
    public ResponseEntity<NotificationResponse> send(
            @Valid @RequestBody SendNotificationRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

        NotificationIngestService.IngestResult result = ingestService.ingest(request, idempotencyKey);
        NotificationResponse body = NotificationResponse.of(result.notification(), result.duplicate());
        return ResponseEntity
                .status(result.duplicate() ? HttpStatus.OK : HttpStatus.ACCEPTED)
                .body(body);
    }

    @GetMapping("/{id}")
    public NotificationResponse get(@PathVariable UUID id) {
        return NotificationResponse.of(queryService.require(id));
    }

    @GetMapping
    public PageResponse<NotificationResponse> search(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) Channel channel,
            @RequestParam(required = false) NotificationStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        PageRequest pageable = PageRequest.of(
                Math.max(0, page),
                Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return PageResponse.of(queryService.search(userId, channel, status, pageable), NotificationResponse::of);
    }

    /** Per-attempt delivery history, including the errors that triggered each retry. */
    @GetMapping("/{id}/attempts")
    public List<DeliveryAttemptResponse> attempts(@PathVariable UUID id) {
        return queryService.attemptsFor(id).stream().map(DeliveryAttemptResponse::of).toList();
    }
}
