package com.healthcheck.chat.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.healthcheck.chat.ai.ParsedRequest;
import com.healthcheck.chat.ai.ParsedRequest.Action;
import com.healthcheck.chat.aws.EcsGateway;
import com.healthcheck.chat.aws.EcsSettings;
import com.healthcheck.chat.aws.ServiceState;

/**
 * Checks that only allowed changes get through. No AWS or OpenAI calls involved.
 */
class RequestValidatorTest {

    // Same settings as application.yml defaults: max 5 tasks
    private final RequestValidator validator = new RequestValidator(
            new EcsSettings("ap-south-1", "ecs-health-check-app", "ecs-health-check-app", 10, 5));

    // Current service: 1 task, 256 CPU / 512 MB, image v1, one env var
    private final ServiceState state = new ServiceState(1, 1, 256, 512,
            "123.dkr.ecr.ap-south-1.amazonaws.com/ecs-health-check-app:v1",
            "arn:aws:ecs:ap-south-1:123:task-definition/ecs-health-check-app:1",
            Map.of("APP_MESSAGE", "Hello from ECS"));

    // ---------- scale ----------

    @Test
    void scaleWithinLimitsIsAccepted() {
        assertThat(validator.validate(scale(3), state)).isEqualTo(new Change.Scale(3));
        assertThat(validator.validate(scale(0), state)).isEqualTo(new Change.Scale(0));
    }

    @Test
    void scaleOutsideLimitsIsRejected() {
        assertThatThrownBy(() -> validator.validate(scale(6), state)).isInstanceOf(RejectedRequestException.class);
        assertThatThrownBy(() -> validator.validate(scale(-1), state)).isInstanceOf(RejectedRequestException.class);
    }

    @Test
    void scaleWithoutNumberIsRejected() {
        assertThatThrownBy(() -> validator.validate(request(Action.SCALE), state))
                .isInstanceOf(RejectedRequestException.class);
    }

    @Test
    void scaleToCurrentCountIsNoChange() {
        assertThat(validator.validate(scale(1), state)).isInstanceOf(Change.NoChange.class);
    }

    // ---------- resources ----------

    @Test
    void validFargateCombinationIsAccepted() {
        assertThat(validator.validate(resources(512, 1024), state)).isEqualTo(new Change.Resources(512, 1024));
    }

    @Test
    void onlyMemoryGivenKeepsCurrentCpu() {
        assertThat(validator.validate(resources(null, 1024), state)).isEqualTo(new Change.Resources(256, 1024));
    }

    @Test
    void invalidFargateCombinationIsRejected() {
        // 256 CPU allows max 2048 MB
        assertThatThrownBy(() -> validator.validate(resources(256, 4096), state))
                .isInstanceOf(RejectedRequestException.class)
                .hasMessageContaining("512, 1024 or 2048");
        // memory not in 1 GB steps
        assertThatThrownBy(() -> validator.validate(resources(1024, 3000), state))
                .isInstanceOf(RejectedRequestException.class);
        // unknown CPU size
        assertThatThrownBy(() -> validator.validate(resources(300, 1024), state))
                .isInstanceOf(RejectedRequestException.class);
    }

    @Test
    void fargateSizeTable() {
        assertThat(RequestValidator.FargateSizes.isValid(256, 512)).isTrue();
        assertThat(RequestValidator.FargateSizes.isValid(256, 3072)).isFalse();
        assertThat(RequestValidator.FargateSizes.isValid(512, 4096)).isTrue();
        assertThat(RequestValidator.FargateSizes.isValid(1024, 2048)).isTrue();
        assertThat(RequestValidator.FargateSizes.isValid(1024, 1024)).isFalse();
        assertThat(RequestValidator.FargateSizes.isValid(2048, 16384)).isTrue();
        assertThat(RequestValidator.FargateSizes.isValid(4096, 30720)).isTrue();
        assertThat(RequestValidator.FargateSizes.isValid(4096, 31744)).isFalse();
    }

    // ---------- env vars ----------

    @Test
    void setEnvIsAccepted() {
        assertThat(validator.validate(setEnv("APP_COLOR", "blue"), state))
                .isEqualTo(new Change.SetEnv("APP_COLOR", "blue"));
    }

    @Test
    void invalidOrReservedEnvNameIsRejected() {
        assertThatThrownBy(() -> validator.validate(setEnv("1BAD", "x"), state))
                .isInstanceOf(RejectedRequestException.class);
        assertThatThrownBy(() -> validator.validate(setEnv("MY-VAR", "x"), state))
                .isInstanceOf(RejectedRequestException.class);
        assertThatThrownBy(() -> validator.validate(setEnv("AWS_REGION", "x"), state))
                .isInstanceOf(RejectedRequestException.class);
    }

    @Test
    void removingUnknownEnvIsRejected() {
        assertThatThrownBy(() -> validator.validate(removeEnv("APP_COLOR"), state))
                .isInstanceOf(RejectedRequestException.class)
                .hasMessageContaining("APP_MESSAGE");
        assertThat(validator.validate(removeEnv("APP_MESSAGE"), state))
                .isEqualTo(new Change.RemoveEnv("APP_MESSAGE"));
    }

    // ---------- image tag ----------

    @Test
    void imageTagRules() {
        assertThat(validator.validate(imageTag("v2"), state)).isEqualTo(new Change.ImageTag("v2"));
        assertThat(validator.validate(imageTag("v1"), state)).isInstanceOf(Change.NoChange.class);
        assertThatThrownBy(() -> validator.validate(imageTag("bad tag!"), state))
                .isInstanceOf(RejectedRequestException.class);
    }

    // ---------- status / unclear ----------

    @Test
    void statusAndUnclear() {
        assertThat(validator.validate(request(Action.STATUS), state)).isEqualTo(new Change.Status());

        ParsedRequest unclear = new ParsedRequest(Action.UNCLEAR, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.of("Please ask one change at a time."));
        assertThatThrownBy(() -> validator.validate(unclear, state))
                .isInstanceOf(RejectedRequestException.class)
                .hasMessage("Please ask one change at a time.");
    }

    // ---------- image name helpers ----------

    @Test
    void imageNameHelpers() {
        String image = "123.dkr.ecr.ap-south-1.amazonaws.com/ecs-health-check-app:v1";
        assertThat(EcsGateway.repositoryName(image)).isEqualTo("ecs-health-check-app");
        assertThat(EcsGateway.withTag(image, "v2")).isEqualTo("123.dkr.ecr.ap-south-1.amazonaws.com/ecs-health-check-app:v2");
        assertThat(state.imageTag()).isEqualTo("v1");
        assertThat(state.revision()).isEqualTo("1");
    }

    // ---------- helpers to build what the model would return ----------

    private static ParsedRequest request(Action action) {
        return new ParsedRequest(action, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static ParsedRequest scale(int count) {
        return new ParsedRequest(Action.SCALE, Optional.of(count), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static ParsedRequest resources(Integer cpu, Integer memory) {
        return new ParsedRequest(Action.SET_RESOURCES, Optional.empty(), Optional.ofNullable(cpu), Optional.ofNullable(memory),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static ParsedRequest setEnv(String name, String value) {
        return new ParsedRequest(Action.SET_ENV, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(name), Optional.of(value), Optional.empty(), Optional.empty());
    }

    private static ParsedRequest removeEnv(String name) {
        return new ParsedRequest(Action.REMOVE_ENV, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(name), Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static ParsedRequest imageTag(String tag) {
        return new ParsedRequest(Action.SET_IMAGE_TAG, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(tag), Optional.empty());
    }
}
