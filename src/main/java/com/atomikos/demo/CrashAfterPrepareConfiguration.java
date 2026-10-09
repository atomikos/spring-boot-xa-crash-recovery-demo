package com.atomikos.demo;

import java.sql.SQLException;

import javax.sql.XAConnection;
import javax.sql.XADataSource;
import javax.transaction.xa.XAException;
import javax.transaction.xa.Xid;

import org.postgresql.core.BaseConnection;
import org.postgresql.xa.PGXAConnection;
import org.postgresql.xa.PGXADataSource;
import org.springframework.boot.jdbc.XADataSourceWrapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import com.atomikos.spring.AtomikosXADataSourceWrapper;

/**
 * Demo plumbing for {@code ./demo.sh crash after-prepare}: hands Atomikos the
 * PostgreSQL {@link XADataSource} as usual, but its connections call
 * {@link CrashSwitch} right before the phase-2 commit.
 * Not something a real application needs.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!naive & !local-tx")
class CrashAfterPrepareConfiguration {

    @Bean
    XADataSourceWrapper xaDataSourceWrapper(CrashSwitch crashSwitch) {
        AtomikosXADataSourceWrapper atomikos = new AtomikosXADataSourceWrapper();
        return postgres -> atomikos.wrapDataSource(new CrashingXADataSource((PGXADataSource) postgres, crashSwitch));
    }

    private static class CrashingXADataSource extends PGXADataSource {

        private final transient CrashSwitch crashSwitch;

        // the same database and settings as the one Spring Boot configured
        CrashingXADataSource(PGXADataSource postgres, CrashSwitch crashSwitch) {
            setURL(postgres.getURL());
            setUser(postgres.getUser());
            setPassword(postgres.getPassword());
            this.crashSwitch = crashSwitch;
        }

        @Override
        public XAConnection getXAConnection(String user, String password) throws SQLException {
            BaseConnection connection = (BaseConnection) getConnection(user, password);
            return new PGXAConnection(connection) {

                @Override
                public void commit(Xid xid, boolean onePhase) throws XAException {
                    if (!onePhase) {
                        crashSwitch.beforePhase2Commit(); // two-phase commit, after a successful prepare
                    }
                    super.commit(xid, onePhase);
                }

            };
        }

    }

}
