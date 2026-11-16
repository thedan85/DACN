package com.project.dacn.support;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Test-only DataSource wrapper that records every executed SQL statement.
 * Used to prove the inbox snapshot's exact query budget. A before-execute hook
 * allows a competing transaction to commit between the service's reads.
 */
public final class CountingDataSource implements DataSource {

    private final DataSource delegate;
    private final List<String> statements = Collections.synchronizedList(new ArrayList<>());
    private volatile Consumer<String> beforeExecute = sql -> { };

    public CountingDataSource(DataSource delegate) {
        this.delegate = delegate;
    }

    /** Hook runs on the test thread immediately before a statement executes. */
    public void onBeforeExecute(Consumer<String> hook) {
        this.beforeExecute = hook == null ? sql -> { } : hook;
    }

    public void reset() {
        statements.clear();
        beforeExecute = sql -> { };
    }

    public List<String> statements() {
        return List.copyOf(statements);
    }

    public int count() {
        return statements.size();
    }

    @Override
    public Connection getConnection() throws SQLException {
        return countingConnection(delegate.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return countingConnection(delegate.getConnection(username, password));
    }

    private Connection countingConnection(Connection real) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("prepareStatement".equals(name) || "prepareCall".equals(name)) {
                        Object statement = invoke(real, method, args);
                        return countingStatement(statement, (String) args[0]);
                    }
                    if ("createStatement".equals(name)) {
                        Object statement = invoke(real, method, args);
                        return countingStatement(statement, "<dynamic>");
                    }
                    if ("unwrap".equals(name) && args != null && args.length == 1
                            && args[0] instanceof Class<?> type && type.isInstance(real)) {
                        return real;
                    }
                    return invoke(real, method, args);
                });
    }

    private Object countingStatement(Object real, String defaultSql) {
        boolean prepared = real instanceof PreparedStatement;
        Class<?> api = prepared ? PreparedStatement.class : Statement.class;
        return Proxy.newProxyInstance(api.getClassLoader(), new Class<?>[]{api},
                (proxy, method, args) -> {
                    if (method.getName().startsWith("execute")) {
                        String sql = defaultSql;
                        if (args != null && args.length > 0 && args[0] instanceof String supplied) {
                            sql = supplied;
                        }
                        beforeExecute.accept(sql);
                        statements.add(sql);
                    }
                    return invoke(real, method, args);
                });
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException exception) {
            throw exception.getCause();
        }
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        return delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return delegate.isWrapperFor(iface) || iface.isInstance(this);
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return delegate.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        delegate.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        delegate.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return delegate.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws java.sql.SQLFeatureNotSupportedException {
        return delegate.getParentLogger();
    }
}
