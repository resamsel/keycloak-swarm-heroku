package org.i5y.keycloak;

import com.heroku.sdk.jdbc.DatabaseUrl;
import org.wildfly.swarm.config.undertow.BufferCache;
import org.wildfly.swarm.config.undertow.HandlerConfiguration;
import org.wildfly.swarm.config.undertow.Server;
import org.wildfly.swarm.config.undertow.ServletContainer;
import org.wildfly.swarm.config.undertow.server.HTTPListener;
import org.wildfly.swarm.config.undertow.server.Host;
import org.wildfly.swarm.config.undertow.servlet_container.JSPSetting;
import org.wildfly.swarm.config.undertow.servlet_container.WebsocketsSetting;
import org.wildfly.swarm.container.Container;
import org.wildfly.swarm.spi.api.Fraction;
import org.wildfly.swarm.spi.api.SocketBinding;
import org.wildfly.swarm.datasources.DatasourcesFraction;
import org.wildfly.swarm.keycloak.server.KeycloakServerFraction;
import org.wildfly.swarm.undertow.UndertowFraction;

public class Main {

    public static void main(String[] args) throws Exception {

        // Heroku requires you bind to the specified PORT
        String port = System.getenv("PORT");
        if (port != null) {
            System.setProperty("jboss.http.port", port);
        }

        Container container = new Container();

        // Extract the postgres connection details from the Heroku environment variable
        // (which is not a JDBC URL)
        DatabaseUrl databaseUrl = DatabaseUrl.extract();
        
        System.out.println("Database URL: " + databaseUrl.jdbcUrl());

        // Configure the KeycloakDS datasource to use postgres
        DatasourcesFraction datasourcesFraction = new DatasourcesFraction();
        datasourcesFraction
                .jdbcDriver("org.postgresql", (d) -> {
                    d.driverDatasourceClassName("org.postgresql.ds.PGSimpleDataSource");
                    d.xaDatasourceClass("org.postgresql.xa.PGXADataSource");
                    d.driverModuleName("org.postgresql");
                })
                .dataSource("KeycloakDS", (ds) -> {
                    ds.driverName("org.postgresql");
                    ds.connectionUrl(databaseUrl.jdbcUrl());
                    ds.userName(databaseUrl.username());
                    ds.password(databaseUrl.password());
                });

        container.fraction(datasourcesFraction);

        // Set up container config to take advantage of HTTPS in heroku
        container.fraction(new Fraction() {
            @Override
            public String simpleName() {
                return "proxy-https";
            }

            @Override
            public void initialize(Fraction.InitContext initContext) {
                initContext.socketBinding(new SocketBinding("proxy-https").port(443));
            }

        });

        UndertowFraction undertowFraction = new UndertowFraction();
        undertowFraction
                .server(new Server("default-server")
                        .httpListener(new HTTPListener("default")
                                .socketBinding("http")
                                .redirectSocket("proxy-https")
                                .proxyAddressForwarding(true))
                        .host(new Host("default-host")))
                .bufferCache(new BufferCache("default"))
                .servletContainer(new ServletContainer("default")
                        .websocketsSetting(new WebsocketsSetting())
                        .jspSetting(new JSPSetting()))
                .handlerConfiguration(new HandlerConfiguration());

        container.fraction(undertowFraction);

        // Finally, add KeycloakServer...
        KeycloakServerFraction keycloakServerFraction = new KeycloakServerFraction();

        container.fraction(keycloakServerFraction);

        // And start!
        container.start();
    }
}
