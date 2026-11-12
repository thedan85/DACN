package com.project.dacn.service.order;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReservationPolicyTest {

    @Test
    void absentDeploymentConfigurationDisablesActivationAndBothIndependentPauses() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            ReservationPolicy policy = context.getBean(ReservationPolicy.class);
            assertThat(policy.isActive()).isFalse();
            assertThat(policy.isOnlineWorkPaused()).isFalse();
            assertThat(policy.isSweepingPaused()).isFalse();
        });
    }

    @ParameterizedTest
    @CsvSource({
            "false,false,false", "false,false,true", "false,true,false", "false,true,true",
            "true,false,false", "true,false,true", "true,true,false", "true,true,true"
    })
    void pausesNeverChangeActivationAndRemainIndependentAcrossRestarts(
            boolean active, boolean onlinePaused, boolean sweepPaused) {
        for (int restart = 0; restart < 2; restart++) {
            runner().withPropertyValues(
                    "app.inventory.reservation-policy-enabled=" + active,
                    "app.inventory.online-work-paused=" + onlinePaused,
                    "app.inventory.expiry-sweeping-paused=" + sweepPaused)
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        ReservationPolicy policy = context.getBean(ReservationPolicy.class);
                        assertThat(policy.isActive()).isEqualTo(active);
                        assertThat(policy.isOnlineWorkPaused()).isEqualTo(onlinePaused);
                        assertThat(policy.isSweepingPaused()).isEqualTo(sweepPaused);
                    });
        }
    }

    @Test
    void productionYamlKeepsInventoryAndDeliveryDisabledAndHikariAtFive() {
        yamlRunner().run(context -> {
            assertThat(context).hasNotFailed();
            ReservationPolicy policy = context.getBean(ReservationPolicy.class);
            assertThat(policy.isActive()).isFalse();
            assertThat(policy.isOnlineWorkPaused()).isFalse();
            assertThat(policy.isSweepingPaused()).isFalse();
            assertThat(context.getEnvironment().getProperty("app.notifications.delivery-enabled", Boolean.class))
                    .isFalse();
            assertThat(context.getEnvironment().getProperty("app.realtime.enabled", Boolean.class)).isFalse();
            assertThat(context.getEnvironment().getProperty("spring.datasource.hikari.maximum-pool-size", Integer.class))
                    .isEqualTo(5);
        });
    }

    @Test
    void productionYamlBindsOperatorEnvironmentAliasesWithoutCouplingPausesToActivation() {
        yamlRunner().withInitializer(context -> context.getEnvironment().getPropertySources()
                        .addFirst(new MapPropertySource("operator-fixture", Map.of(
                                "INVENTORY_RESERVATION_POLICY_ENABLED", "true",
                                "INVENTORY_ONLINE_WORK_PAUSED", "true",
                                "INVENTORY_EXPIRY_SWEEPING_PAUSED", "false"))))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ReservationPolicy policy = context.getBean(ReservationPolicy.class);
                    assertThat(policy.isActive()).isTrue();
                    assertThat(policy.isOnlineWorkPaused()).isTrue();
                    assertThat(policy.isSweepingPaused()).isFalse();
                });
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withInitializer(context -> {
            // Keep deployment secrets and live activation out of these property-only contexts.
            context.getEnvironment().getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
            context.getEnvironment().getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        }).withUserConfiguration(ReservationPolicy.class);
    }

    private ApplicationContextRunner yamlRunner() {
        return runner().withInitializer(context -> {
            try {
                for (var source : new YamlPropertySourceLoader().load("application",
                        new ClassPathResource("application.yml"))) {
                    context.getEnvironment().getPropertySources().addLast(source);
                }
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });
    }
}
