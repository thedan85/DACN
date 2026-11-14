package com.project.dacn.support;

import org.springframework.jdbc.datasource.AbstractDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Test-only, thread-local JDBC round-trip profiling; never records bind values or connection URLs. */
public final class ExpiryJdbcProfile extends AbstractDataSource {
    private static final ThreadLocal<List<String>> ACTIVE = new ThreadLocal<>();
    private final DataSource delegate;

    public ExpiryJdbcProfile(DataSource delegate) {
        this.delegate = delegate;
    }

    public static void start() {
        ACTIVE.set(new ArrayList<>());
    }

    public static List<String> stop() {
        List<String> result = ACTIVE.get();
        ACTIVE.remove();
        return result;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return connection(delegate.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return connection(delegate.getConnection(username, password));
    }

    private Connection connection(Connection connection) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    long start = System.nanoTime();
                    try {
                        Object result = method.invoke(connection, args);
                        if (result instanceof PreparedStatement statement) {
                            return statement(statement, (String) args[0], PreparedStatement.class);
                        }
                        if (result instanceof Statement statement) {
                            return statement(statement, "", Statement.class);
                        }
                        return result;
                    } catch (InvocationTargetException error) {
                        throw error.getCause();
                    } finally {
                        if (List.of("commit", "rollback", "setAutoCommit", "setReadOnly").contains(method.getName())) {
                            record(method.getName(), start);
                        }
                    }
                });
    }

    private Object statement(Statement statement, String sql, Class<?> type) {
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> {
                    long start = System.nanoTime();
                    try {
                        return method.invoke(statement, args);
                    } catch (InvocationTargetException error) {
                        throw error.getCause();
                    } finally {
                        if (method.getName().startsWith("execute")) {
                            String query = sql.isEmpty() && args != null && args.length > 0
                                    && args[0] instanceof String text ? text : sql;
                            record(query.replaceAll("'[^']*'", "'?'").replaceAll("\\s+", " ").trim(), start);
                        }
                    }
                });
    }

    private static void record(String operation, long start) {
        List<String> profile = ACTIVE.get();
        if (profile != null) {
            profile.add(TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - start) + "us " + operation);
        }
    }
}
