package com.project.dacn.integration.mail;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;

/**
 * One shared watchdog and one sending slot for the whole application. Creating
 * the executor enables no listener and opens no connection; delivery stays
 * disabled until a consumer is explicitly wired.
 */
@Configuration
@EnableConfigurationProperties(MailContentProperties.class)
public class MailDeliveryConfig {

    @Bean(destroyMethod = "shutdown")
    public ScheduledExecutorService smtpWatchdogScheduler() {
        ScheduledThreadPoolExecutor pool = new ScheduledThreadPoolExecutor(1, r -> {
            Thread thread = new Thread(r, "smtp-attempt-watchdog");
            thread.setDaemon(true);
            return thread;
        });
        pool.setRemoveOnCancelPolicy(true);
        return pool;
    }

    @Bean
    public Semaphore emailSendingSlot() {
        return new Semaphore(1);
    }

    @Bean
    public SmtpAttemptExecutor smtpAttemptExecutor(
            ScheduledExecutorService smtpWatchdogScheduler,
            Semaphore emailSendingSlot,
            @Value("${spring.mail.host:localhost}") String host,
            @Value("${spring.mail.port:25}") int port,
            @Value("${MAIL_USERNAME:}") String username,
            @Value("${MAIL_APP_PASSWORD:}") String password,
            @Value("${spring.mail.properties.mail.smtp.starttls.enable:false}") boolean startTls) {
        SmtpAttemptExecutor.Target target =
                new SmtpAttemptExecutor.Target(host, port, username, password, startTls);
        return new SmtpAttemptExecutor(target, smtpWatchdogScheduler, emailSendingSlot);
    }
}
