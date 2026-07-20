package com.chenmingqiang.wirelesssim.task.infrastructure.worker;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Java与Python Worker之间的内部接口配置；令牌只能通过环境变量注入。 */
@ConfigurationProperties(prefix = "simulation.worker-api")
@Validated
public record WorkerApiProperties(
        String token,
        @NotBlank String modelId,
        @Min(1) @Max(1000) int evaluationEpisodes
) {
}
