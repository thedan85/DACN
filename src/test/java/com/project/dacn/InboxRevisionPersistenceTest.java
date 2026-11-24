package com.project.dacn;

import com.project.dacn.entity.auth.Role;
import com.project.dacn.entity.auth.User;
import com.project.dacn.repository.auth.RoleRepository;
import com.project.dacn.repository.auth.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = "spring.jpa.properties.hibernate.session_factory.statement_inspector="
        + "com.project.dacn.InboxRevisionPersistenceTest$SqlCapture")
@ActiveProfiles("test")
class InboxRevisionPersistenceTest {

    public static class SqlCapture implements StatementInspector {
        static final ThreadLocal<List<String>> queries = ThreadLocal.withInitial(ArrayList::new);

        @Override
        public String inspect(String sql) {
            queries.get().add(sql);
            return sql;
        }
    }

    @Autowired RoleRepository roleRepository;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbc;
    @PersistenceContext EntityManager entityManager;

    @AfterEach
    void clearCapturedSql() {
        SqlCapture.queries.remove();
    }

    @Test
    void ordinaryInsertUsesDatabaseDefaultAndOmitsRevisionColumn() {
        Role role = roleRepository.save(Role.builder().code("rev-ins").name("R").build());
        SqlCapture.queries.get().clear();

        User user = userRepository.saveAndFlush(User.builder()
                .email("rev-ins@x.com").passwordHash("h").role(role).build());

        assertThat(SqlCapture.queries.get()).filteredOn(sql -> sql.toLowerCase().startsWith("insert"))
                .hasSize(1).allSatisfy(sql -> assertThat(sql).doesNotContain("inbox_revision"));
        assertThat(revision(user.getId())).isEqualTo(0L);
    }

    @Test
    void staleAuthUpdateKeepsExternallyIncrementedRevision() {
        Role role = roleRepository.save(Role.builder().code("rev-upd").name("R").build());
        User saved = userRepository.saveAndFlush(User.builder()
                .email("rev-upd@x.com").passwordHash("h").role(role).build());
        Long id = saved.getId();
        entityManager.clear();

        User stale = userRepository.findById(id).orElseThrow();
        jdbc.update("update users set inbox_revision = ? where id = ?", 7L, id);
        SqlCapture.queries.get().clear();

        stale.setLastLogin(Instant.parse("2026-11-17T00:00:00Z"));
        userRepository.saveAndFlush(stale);

        List<String> updates = SqlCapture.queries.get().stream()
                .filter(sql -> sql.toLowerCase().startsWith("update")).toList();
        assertThat(updates).isNotEmpty().allSatisfy(sql -> assertThat(sql).doesNotContain("inbox_revision"));
        entityManager.clear();
        assertThat(revision(id)).isEqualTo(7L);
    }

    private long revision(Long id) {
        return jdbc.queryForObject("select inbox_revision from users where id = ?", Long.class, id);
    }
}
