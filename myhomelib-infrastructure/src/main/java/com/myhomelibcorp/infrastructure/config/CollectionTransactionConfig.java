package com.myhomelibcorp.infrastructure.config;

import com.myhomelibcorp.infrastructure.collection.CollectionManager;
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

    @Bean
    public DataSource collectionDataSource(CollectionManager collectionManager) {
        return new DataSource() {
            @Override
            public Connection getConnection() throws SQLException {
                DataSource currentDs = collectionManager.getCurrentDataSource();
                if (currentDs == null) {
                    throw new SQLException("Поточна колекція не вибрана");
                }
                return currentDs.getConnection();
            }

            @Override
            public Connection getConnection(String username, String password) throws SQLException {
                return getConnection();
            }

            @Override
            public PrintWriter getLogWriter() throws SQLException {
                return null;
            }

            @Override
            public void setLogWriter(PrintWriter out) throws SQLException {
                // не потрібно
            }

            @Override
            public void setLoginTimeout(int seconds) throws SQLException {
                // не потрібно
            }

            @Override
            public int getLoginTimeout() throws SQLException {
                return 0;
            }

            @Override
            public Logger getParentLogger() throws SQLFeatureNotSupportedException {
                throw new SQLFeatureNotSupportedException();
            }

            @Override
            public <T> T unwrap(Class<T> iface) throws SQLException {
                throw new SQLException("Not a wrapper");
            }

            @Override
            public boolean isWrapperFor(Class<?> iface) throws SQLException {
                return false;
            }
        };
    }

    @Bean(name = "collectionTransactionManager")
    public PlatformTransactionManager collectionTransactionManager(DataSource collectionDataSource) {
        return new DataSourceTransactionManager(collectionDataSource);
    }

    @Bean(name = "collectionTransactionTemplate")
    public TransactionTemplate collectionTransactionTemplate(
            PlatformTransactionManager collectionTransactionManager) {
        return new TransactionTemplate(collectionTransactionManager);
    }
}