/**
 * Copyright (C) FuseSource, Inc.
 * http://fusesource.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.fusesource.fabric.web;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.curator.framework.state.ConnectionStateListener;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.Service;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.fusesource.fabric.api.Container;
import org.fusesource.fabric.api.FabricService;
import org.fusesource.fabric.service.support.AbstractComponent;
import org.fusesource.fabric.service.support.ValidatingReference;
import org.fusesource.fabric.zookeeper.ZkPath;
import org.ops4j.pax.web.service.spi.ServletEvent;
import org.ops4j.pax.web.service.spi.ServletListener;
import org.ops4j.pax.web.service.spi.WebEvent;
import org.ops4j.pax.web.service.spi.WebListener;
import org.osgi.framework.Bundle;
import org.osgi.service.component.ComponentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

import static org.fusesource.fabric.zookeeper.utils.ZooKeeperUtils.delete;
import static org.fusesource.fabric.zookeeper.utils.ZooKeeperUtils.setData;

@Component(name = "org.fusesource.fabric.web", description = "Fabric Web Registration Handler", immediate = true)
@Service({WebListener.class, ServletListener.class, ConnectionStateListener.class})
public class FabricWebRegistrationHandler extends AbstractComponent implements WebListener, ServletListener, ConnectionStateListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(FabricWebRegistrationHandler.class);

    private final Map<Bundle, WebEvent> webEvents = new HashMap<Bundle, WebEvent>();
    private final Map<Bundle, Map<String, ServletEvent>> servletEvents = new HashMap<Bundle, Map<String, ServletEvent>>();

    @Reference(referenceInterface = FabricService.class)
    private final ValidatingReference<FabricService> fabricService = new ValidatingReference<FabricService>();
    @Reference(referenceInterface = CuratorFramework.class)
    private final ValidatingReference<CuratorFramework> curator = new ValidatingReference<CuratorFramework>();

    @Activate
    synchronized void activate(ComponentContext context) {
        activateComponent(context);
    }

    @Deactivate
    synchronized void deactivate() {
        deactivateComponent();
    }

    @Override
    public void stateChanged(CuratorFramework client, ConnectionState newState) {
        switch (newState) {
            case CONNECTED:
            case RECONNECTED:
                replay();
        }
    }

    @Override
    public void webEvent(WebEvent webEvent) {
        webEvents.put(webEvent.getBundle(), webEvent);
            switch (webEvent.getType()) {
                case WebEvent.DEPLOYING:
                    break;
                case WebEvent.DEPLOYED:
                    registerWebapp(fabricService.get().getCurrentContainer(), webEvent);
                    break;
                default:
                    unRegisterWebapp(fabricService.get().getCurrentContainer(), webEvent);
            }
    }

    @Override
    public void servletEvent(ServletEvent servletEvent) {
        WebEvent webEvent = webEvents.get(servletEvent.getBundle());
        if (webEvent != null || servletEvent.getAlias() == null) {
            // this servlet is part of a web application, ignore it
            return;
        }
        Map<String, ServletEvent> events = servletEvents.get(servletEvent.getBundle());
        if (events == null) {
            events = new HashMap<String, ServletEvent>();
            servletEvents.put(servletEvent.getBundle(), events);
        }
        events.put(servletEvent.getAlias(), servletEvent);
        if (curator != null && curator.get().getZookeeperClient().isConnected()) {
            switch (servletEvent.getType()) {
                case ServletEvent.DEPLOYING:
                    break;
                case ServletEvent.DEPLOYED:
                    registerServlet(fabricService.get().getCurrentContainer(), servletEvent);
                    break;
                default:
                    unregisterServlet(fabricService.get().getCurrentContainer(), servletEvent);
                    break;
            }
        }
    }

    /**
     * Replays again all events.
     */
    private void replay() {
        for (Map.Entry<Bundle, WebEvent> entry : webEvents.entrySet()) {
            webEvent(entry.getValue());
        }
        for (Map.Entry<Bundle, Map<String, ServletEvent>> entry : servletEvents.entrySet()) {
            Map<String, ServletEvent> servletEventMap = entry.getValue();
            for (Map.Entry<String, ServletEvent> sentry : servletEventMap.entrySet()) {
                servletEvent(sentry.getValue());
            }
        }
    }

    void registerServlet(Container container, ServletEvent servletEvent) {
        String id = container.getId();
        String url = "${zk:" + id + "/http}" + servletEvent.getAlias();

        String name = servletEvent.getBundle().getSymbolicName();
        setJolokiaUrl(container, url, name);

        String json = "{\"id\":\"" + id + "\", \"services\":[\"" + url + "\"],\"container\":\"" + id + "\"}";
        try {
            //We don't want to register / it's fabric-redirect for hawtio
            if (!servletEvent.getAlias().equals("/")) {
                String path = createServletPath(servletEvent, id);
                setData(curator.get(), path, json, CreateMode.EPHEMERAL);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to register servlet {}.", servletEvent.getAlias(), e);
        }
    }

    void unregisterServlet(Container container, ServletEvent servletEvent) {
        try {
            String name = servletEvent.getBundle().getSymbolicName();
            clearJolokiaUrl(container, name);

            String id = container.getId();
            //We don't want to register / it's fabric-redirect for hawtio
            if (!servletEvent.getAlias().equals("/")) {
                String path = createServletPath(servletEvent, id);
                delete(curator.get(), path);
            }
        } catch (KeeperException.NoNodeException e) {
            // If the node does not exists, ignore the exception
        } catch (Exception e) {
            LOGGER.error("Failed to unregister servlet {}.", servletEvent.getAlias(), e);
        }
    }

    /**
     * Registers a webapp to the registry.
     * @param container
     * @param webEvent
     */
    void registerWebapp(Container container, WebEvent webEvent) {
        String id = container.getId();
        String url = "${zk:" + id + "/http}" + webEvent.getContextPath();

        String name = webEvent.getBundle().getSymbolicName();
        setJolokiaUrl(container, url, name);

        String json = "{\"id\":\"" + id + "\", \"services\":[\"" + url + "\"],\"container\":\"" + id + "\"}";
        try {
            setData(curator.get(), ZkPath.WEBAPPS_CONTAINER.getPath(name,
                    webEvent.getBundle().getVersion().toString(), id), json, CreateMode.EPHEMERAL);
        } catch (Exception e) {
            LOGGER.error("Failed to register webapp {}.", webEvent.getContextPath(), e);
        }
    }


    /**
     * Unregister a webapp from the registry.
     * @param container
     * @param webEvent
     */
    void unRegisterWebapp(Container container, WebEvent webEvent) {
        try {
            String name = webEvent.getBundle().getSymbolicName();
            clearJolokiaUrl(container, name);

            delete(curator.get(), ZkPath.WEBAPPS_CONTAINER.getPath(name,
                    webEvent.getBundle().getVersion().toString(), container.getId()));
        } catch (KeeperException.NoNodeException e) {
            // If the node does not exists, ignore the exception
        } catch (Exception e) {
            LOGGER.error("Failed to unregister webapp {}.", webEvent.getContextPath(), e);
        }
    }


    private String createServletPath(ServletEvent servletEvent, String id) {
        StringBuilder path = new StringBuilder();
        path.append("/fabric/registry/clusters/servlets/")
                .append(servletEvent.getBundle().getSymbolicName()).append("/")
                .append(servletEvent.getBundle().getVersion().toString())
                .append(servletEvent.getAlias()).append("/")
                .append(id);
        return path.toString();
    }

    private void setJolokiaUrl(Container container, String url, String symbolicName) {
        if (symbolicName.contains("jolokia")) {
            container.setJolokiaUrl(url);
            System.setProperty("jolokia.agent", url);
        }
    }

    private void clearJolokiaUrl(Container container, String symbolicName) {
        if (symbolicName.contains("jolokia")) {
            container.setJolokiaUrl(null);
            System.clearProperty("jolokia.agent");
        }
    }

    void bindFabricService(FabricService fabricService) {
        this.fabricService.set(fabricService);
    }

    void unbindFabricService(FabricService fabricService) {
        this.fabricService.set(null);
    }

    void bindCurator(CuratorFramework curator) {
        this.curator.set(curator);
    }

    void unbindCurator(CuratorFramework curator) {
        this.curator.set(null);
    }

}
