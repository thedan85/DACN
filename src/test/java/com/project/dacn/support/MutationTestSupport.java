package com.project.dacn.support;

import com.project.dacn.repository.engagement.NotificationInboxQueries;
import com.project.dacn.repository.engagement.NotificationRepository;
import com.project.dacn.service.engagement.NotificationInboxService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

import javax.sql.DataSource;
import java.util.Properties;

/**
 * Hand-wires the production {@link NotificationInboxService} on an invocation-owned
 * scratch DataSource: real Spring Data JPA repository + JDBC queries sharing one
 * {@link JpaTransactionManager}, so native owned lookup, SQL mutation, and the
 * database-owned revision increment run in one transaction.
 */
public final class MutationTestSupport implements AutoCloseable {

    public final NotificationInboxService service;
    private final LocalContainerEntityManagerFactoryBean factoryBean;

    private MutationTestSupport(NotificationInboxService service,
                                LocalContainerEntityManagerFactoryBean factoryBean) {
        this.service = service;
        this.factoryBean = factoryBean;
    }

    public static MutationTestSupport create(DataSource dataSource) {
        LocalContainerEntityManagerFactoryBean bean = new LocalContainerEntityManagerFactoryBean();
        bean.setDataSource(dataSource);
        bean.setPackagesToScan("com.project.dacn.entity");
        bean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        Properties properties = new Properties();
        properties.put("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
        properties.put("hibernate.hbm2ddl.auto", "none");
        bean.setJpaProperties(properties);
        bean.afterPropertiesSet();
        EntityManagerFactory factory = bean.getObject();

        EntityManager shared = SharedEntityManagerCreator.createSharedEntityManager(factory);
        NotificationRepository repository =
                new JpaRepositoryFactory(shared).getRepository(NotificationRepository.class);

        JpaTransactionManager manager = new JpaTransactionManager(factory);
        manager.setDataSource(dataSource);
        NotificationInboxQueries queries =
                new NotificationInboxQueries(new NamedParameterJdbcTemplate(dataSource));
        return new MutationTestSupport(
                new NotificationInboxService(queries, repository, manager), bean);
    }

    @Override
    public void close() {
        factoryBean.destroy();
    }
}
