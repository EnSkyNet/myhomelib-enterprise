package com.myhomelibcorp.infrastructure.config;

import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;

@Configuration
public class CollectionTransactionConfig {

    @Bean(name = "collectionDataSource")
    public DataSource collectionDataSource(CollectionManager collectionManager) {
        return new DataSource() {
            private DataSource currentDataSource() throws SQLException {
                DataSource current = collectionManager.getCurrentDataSource();
                if (current == null) throw new SQLException("Поточна колекція не вибрана");
                return current;
            }

            @Override
            public Connection getConnection() throws SQLException {
                return currentDataSource().getConnection();
            }

            @Override
            public Connection getConnection(String username, String password) throws SQLException {
                return currentDataSource().getConnection(username, password);
            }

            @Override
            public PrintWriter getLogWriter() throws SQLException {
                return currentDataSource().getLogWriter();
            }

            @Override
            public void setLogWriter(PrintWriter out) throws SQLException {
                currentDataSource().setLogWriter(out);
            }

            @Override
            public void setLoginTimeout(int seconds) throws SQLException {
                currentDataSource().setLoginTimeout(seconds);
            }

            @Override
            public int getLoginTimeout() throws SQLException {
                return currentDataSource().getLoginTimeout();
            }

            @Override
            public Logger getParentLogger() throws SQLFeatureNotSupportedException {
                DataSource current = collectionManager.getCurrentDataSource();
                if (current == null) throw new SQLFeatureNotSupportedException("Поточна колекція не вибрана");
                return current.getParentLogger();
            }

            @Override
            public <T> T unwrap(Class<T> iface) throws SQLException {
                return currentDataSource().unwrap(iface);
            }

            @Override
            public boolean isWrapperFor(Class<?> iface) throws SQLException {
                return currentDataSource().isWrapperFor(iface);
            }
        };
    }

    @Bean(name = "collectionTransactionManager")
    public PlatformTransactionManager collectionTransactionManager(
            @Qualifier("collectionDataSource") DataSource collectionDataSource) {
        return new DataSourceTransactionManager(collectionDataSource);
    }

    @Bean(name = "collectionTransactionTemplate")
    public TransactionTemplate collectionTransactionTemplate(
            @Qualifier("collectionTransactionManager")
            PlatformTransactionManager collectionTransactionManager) {
        return new TransactionTemplate(collectionTransactionManager);
    }
}