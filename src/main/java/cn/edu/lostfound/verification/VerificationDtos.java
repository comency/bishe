package cn.edu.lostfound.verification;

import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** Explicit projections keep internal evidence out of the applicant's response. */
public final class VerificationDtos {
  private VerificationDtos() {}

  public record VerificationSummary(Long userId, String campusId, String status, long version,
      OffsetDateTime expiresAt, LocalDate validThrough, boolean isTest, String reason) {}
  public record Application(Long id, long applicationVersion, String status, String realName,
      String studentNumber, String statement, OffsetDateTime submittedAt, OffsetDateTime reviewedAt,
      LocalDate validThrough, String reason) {}
  public record AdminApplication(Long id, long applicationVersion, String status, String realName,
      String studentNumber, String statement, OffsetDateTime submittedAt, OffsetDateTime reviewedAt,
      LocalDate validThrough, String reason, String method, String evidenceSummary,
      String internalNote, Long reviewerId) {}
  public record AdminSummary(Long userId, String campusId, String status, long version,
      OffsetDateTime expiresAt, LocalDate validThrough, boolean isTest, String reason, String username,
      String realName, Long currentApplicationId, long applicationVersion) {}
  public record Page<T>(List<T> records, long total, int page, int pageSize) {}
  public record MyVerification(VerificationSummary summary, Application currentApplication,
      Page<Application> history) {}
  public record AdminVerification(AdminSummary summary, AdminApplication currentApplication,
      Page<AdminApplication> history) {}

  public record SubmitRequest(@NotNull @PositiveOrZero Long expectedVersion,
      @NotBlank @Size(max=80) String realName, @Size(max=40) String studentNumber,
      @Size(max=500) String statement) {}
  public record ReviewRequest(@NotNull @Positive Long applicationId,
      @NotNull @PositiveOrZero Long expectedVersion, @NotNull Decision decision,
      Method method, @Size(max=500) String evidenceSummary, LocalDate validThrough,
      @Size(max=500) String reason, @Size(max=500) String internalNote) {}
  public record VersionReasonRequest(@NotNull @PositiveOrZero Long expectedVersion,
      @NotBlank @Size(max=500) String reason) {}
  public enum Decision { APPROVED, REJECTED }
  public enum Method { IN_PERSON, ROSTER }
  public enum Status { UNVERIFIED, PENDING, VERIFIED, REJECTED, EXPIRED, REVOKED }
}
