/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2010, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

/**
 *
 */
package org.jboss.as.server.manager;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jboss.as.deployment.client.api.domain.DomainDeploymentManager;
import org.jboss.as.domain.controller.DomainConfigurationPersister;
import org.jboss.as.domain.controller.DomainController;
import org.jboss.as.domain.controller.deployment.DomainDeploymentManagerImpl;
import org.jboss.as.domain.controller.deployment.DomainDeploymentRepository;
import org.jboss.as.model.AbstractServerModelUpdate;
import org.jboss.as.model.DomainModel;
import org.jboss.as.model.HostModel;
import org.jboss.as.model.ManagementElement;
import org.jboss.as.model.RemoteDomainControllerElement;
import org.jboss.as.model.ServerElement;
import org.jboss.as.model.ServerGroupDeploymentElement;
import org.jboss.as.model.UpdateFailedException;
import org.jboss.as.model.socket.InterfaceElement;
import org.jboss.as.process.ProcessManagerProtocol.OutgoingPmCommand;
import org.jboss.as.process.ProcessManagerProtocol.OutgoingPmCommandHandler;
import org.jboss.as.process.RespawnPolicy;
import org.jboss.as.server.ServerState;
import org.jboss.as.server.ServerManagerProtocol.Command;
import org.jboss.as.server.ServerManagerProtocol.ServerToServerManagerCommandHandler;
import org.jboss.as.server.ServerManagerProtocol.ServerToServerManagerProtocolCommand;
import org.jboss.as.server.manager.DirectServerManagerCommunicationHandler.ShutdownListener;
import org.jboss.as.server.manager.management.DomainControllerOperationHandler;
import org.jboss.as.server.manager.management.ManagementCommunicationService;
import org.jboss.as.server.manager.management.ManagementCommunicationServiceInjector;
import org.jboss.as.server.manager.management.ManagementOperationHandlerService;
import org.jboss.as.server.manager.management.ServerManagerOperationHandler;
import org.jboss.as.services.net.NetworkInterfaceBinding;
import org.jboss.as.services.net.NetworkInterfaceService;
import org.jboss.as.threads.ThreadFactoryService;
import org.jboss.logging.Logger;
import org.jboss.msc.inject.InjectionException;
import org.jboss.msc.inject.Injector;
import org.jboss.msc.service.AbstractServiceListener;
import org.jboss.msc.service.BatchBuilder;
import org.jboss.msc.service.BatchServiceBuilder;
import org.jboss.msc.service.Service;
import org.jboss.msc.service.ServiceActivatorContext;
import org.jboss.msc.service.ServiceActivatorContextImpl;
import org.jboss.msc.service.ServiceContainer;
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.ServiceRegistryException;
import org.jboss.msc.service.StartContext;
import org.jboss.msc.service.StartException;
import org.jboss.msc.service.StopContext;
import org.jboss.msc.value.InjectedValue;
import org.jboss.staxmapper.XMLMapper;

/**
 * A ServerManager.
 *
 * @author Brian Stansberry
 * @author Kabir Khan
 */
public class ServerManager implements ShutdownListener {
    private static final Logger log = Logger.getLogger("org.jboss.server.manager");

    static final ServiceName SERVICE_NAME_BASE = ServiceName.JBOSS.append("server", "manager");

    private final ServerManagerEnvironment environment;
    private final StandardElementReaderRegistrar extensionRegistrar;
    private final ProcessManagerCommandHandler processManagerCommmandHandler;
    private final FileRepository fileRepository;
    private volatile ProcessManagerSlave processManagerSlave;
    private final ServerToServerManagerCommandHandler serverCommandHandler = new ServerCommandHandler();
    private volatile DirectServerCommunicationListener directServerCommunicationListener;
    private final ModelManager modelManager;
    private DomainControllerConnection domainControllerConnection;
    private final ServiceContainer serviceContainer = ServiceContainer.Factory.create();
    private final AtomicBoolean serversStarted = new AtomicBoolean();
    private final AtomicBoolean stopping = new AtomicBoolean();

    // TODO figure out concurrency controls
//    private final Lock hostLock = new ReentrantLock();
//    private final Lock domainLock = new ReentrantLock();
    private final Map<String, Server> servers = Collections.synchronizedMap(new HashMap<String, Server>());

    public ServerManager(ServerManagerEnvironment environment) {
        if (environment == null) {
            throw new IllegalArgumentException("bootstrapConfig is null");
        }
        this.environment = environment;
        this.extensionRegistrar = StandardElementReaderRegistrar.Factory.getRegistrar();
        this.modelManager = new ModelManager(environment, extensionRegistrar);
        this.processManagerCommmandHandler = new ProcessManagerCommandHandler();
        this.fileRepository = new LocalFileRepository(environment);
    }

    public String getName() {
        return getHostModel().getName();
    }

    /**
     * Starts the ServerManager. This brings this ServerManager to the point where
     * it has processed it's own configuration file, registered with the DomainController
     * (including starting one if the host configuration specifies that),
     * obtained the domain configuration, and launched any systems needed to make
     * this process manageable by remote clients.
     */
    public void start() {

        modelManager.start();

        // TODO set up logging for this process based on config in Host

        //Start listening for server communication on our socket
        launchDirectServerCommunicationHandler();

        // Start communication with the ProcessManager. This also
        // creates a daemon thread to keep this process alive
        launchProcessManagerSlave();

        final BatchBuilder batchBuilder = serviceContainer.batchBuilder();
        batchBuilder.addListener(new AbstractServiceListener<Object>() {
            @Override
            public void serviceFailed(ServiceController<?> serviceController, StartException reason) {
                log.errorf(reason, "Service [%s] failed.", serviceController.getName());
            }
        });

        final ServiceActivatorContext serviceActivatorContext = new ServiceActivatorContextImpl(batchBuilder);

        // Always activate the management port
        activateManagementCommunication(serviceActivatorContext);

        if (getHostModel().getLocalDomainControllerElement() != null) {
            activateLocalDomainController(serviceActivatorContext);
        } else {
            activateRemoteDomainControllerConnection(serviceActivatorContext);
        }
        try {
            batchBuilder.install();
        } catch (ServiceRegistryException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * The connection from a server to SM was closed
     */
    @Override
    public void connectionClosed(String processName) {
        if (stopping.get())
            return;

        Server server = servers.get(processName);

        if (server == null) {
            log.errorf("No server called %s with a closed connection", processName);
            return;
        }

        ServerState state = server.getState();
        if (state == ServerState.STOPPED || state == ServerState.STOPPING || state == ServerState.MAX_FAILED) {
            log.debugf("Ignoring closed connection for server %s in the %s state", processName, state);
            return;
        }

        log.infof("Server %s connection was closed, tell it to reconnect", processName);
        try {
            processManagerSlave.reconnectServer(processName, directServerCommunicationListener.getSmAddress(), directServerCommunicationListener.getSmPort());
        } catch (IOException e) {
            if (stopping.get())
                return;

            log.error("Failed to send RECONNECT_SERVER", e);
        }
    }

    public <R> R applyUpdate(final String serverName, final AbstractServerModelUpdate<R> update) throws UpdateFailedException {
        final Server server = servers.get(serverName);
        if(server == null) {
            throw new UpdateFailedException("No server available with name " + serverName);
        }
        return server.applyUpdate(update);
    }

    /**
     * Callback for when we receive the SERVER_AVAILABLE message from a Server
     *
     * @param serverName the name of the server
     */
    void availableServer(String serverName) {
        try {
            Server server = servers.get(serverName);
            if (server == null) {
                log.errorf("No server called %s available", serverName);
                return;
            }
            checkState(server, ServerState.BOOTING);

            server.setState(ServerState.AVAILABLE);
            log.infof("Sending config to server %s", serverName);
            server.startServerProcess();
            server.setState(ServerState.STARTING);
        } catch (IOException e) {
            log.errorf(e, "Could not start server %s", serverName);
        }
    }

    /**
     * Callback for when we receive the SHUTDOWN message from PM
     */
    public void stop() {
        if (stopping.getAndSet(true)) {
            return;
        }

        log.info("Stopping ServerManager");
        directServerCommunicationListener.shutdown();
        if(domainControllerConnection != null) {
            domainControllerConnection.unregister();
        }
        serviceContainer.shutdown();
        // FIXME stop any local DomainController, stop other internal SM services
    }

    /**
     * Callback for when we receive the
     * {@link ServerToServerManagerProtocolCommand#SERVER_STOPPED}
     * message from a Server
     *
     * @param serverName the name of the server
     */
    void stoppedServer(String serverName) {
        if (stopping.get())
            return;

        Server server = servers.get(serverName);
        if (server == null) {
            log.errorf("No server called %s exists for stop", serverName);
            return;
        }
        checkState(server, ServerState.STOPPING);

        try {
            processManagerSlave.stopProcess(serverName);
        } catch (IOException e) {
            if (stopping.get())
                return;
            log.errorf(e, "Could not stop server %s in PM", serverName);
        }
        try {
            processManagerSlave.removeProcess(serverName);
        } catch (IOException e) {
            if (stopping.get())
                return;
            log.errorf(e, "Could not stop server %s", serverName);
        }
    }

    /**
     * Callback for when we receive the
     * {@link ServerToServerManagerProtocolCommand#SERVER_STARTED}
     * message from a Server
     *
     * @param serverName the name of the server
     */
    void startedServer(String serverName) {
        Server server = servers.get(serverName);
        if (server == null) {
            log.errorf("No server called %s exists for start", serverName);
            return;
        }
        checkState(server, ServerState.STARTING);
        server.setState(ServerState.STARTED);
    }

    /**
     * Callback for when we receive the
     * {@link ServerToServerManagerProtocolCommand#SERVER_START_FAILED}
     * message from a Server
     *
     * @param serverName the name of the server
     */
    void failedStartServer(String serverName) {
        Server server = servers.get(serverName);
        if (server == null) {
            log.errorf("No server called %s exists", serverName);
            return;
        }
        checkState(server, ServerState.STARTING);
        server.setState(ServerState.FAILED);
        respawn(server);
    }

    /**
     * Callback for when we receive the
     * {@link ServerToServerManagerProtocolCommand#SERVER_RECONNECT_STATUS}
     * message from a Server
     *
     * @param serverName the name of the server
     * @param state the server's state
     */
    void reconnectedServer(String serverName, ServerState state) {
        Server server = servers.get(serverName);
        if (server == null) {
            log.errorf("No server found for reconnected server %s", serverName);
            return;
        }

        server.setState(state);

        if (state.isRestartOnReconnect()) {
            try {
                server.startServerProcess();
            } catch (IOException e) {
                log.errorf(e, "Could not start reconnected server %s", server.getServerProcessName());
            }
        }
    }

    private void respawn(Server server){
        try {
            server.stopServerProcess();
        } catch (IOException e) {
            log.errorf(e, "Error stopping server %s before respawning", server.getServerProcessName());
        }


        RespawnPolicy respawnPolicy = server.getRespawnPolicy();
        long timeout = respawnPolicy.getTimeOutMs(server.incrementAndGetRespawnCount());
        if (timeout < 0 ) {
            server.setState(ServerState.MAX_FAILED);
            try {
                server.removeServerProcess();
            } catch (IOException e) {
                log.errorf(e, "Error removing server %s before respawning", server.getServerProcessName());
            }
            return;
        }

        //TODO JBAS-8390 Put in actual sleep
        //Thread.sleep(timeout);

        try {
            server.startServerProcess();
        } catch (IOException e) {
            log.errorf(e, "Error respawning server %s", server.getServerProcessName());
        }
    }

    /**
     * Handles a notification from the ProcessManager that a server has
     * gone down.
     *
     * @param downServerName the process name of the server.
     */
    public void downServer(String downServerName) {
        Server server = servers.get(downServerName);
        if (server == null) {
            log.errorf("No server called %s exists", downServerName);
            return;
        }

        if (environment.isRestart() && server.getState() == ServerState.BOOTING && environment.getServerManagerPort() == 0) {
            //If this was a restarted SM and a server went down while we were down, PM will send the DOWN message. If the port
            //is 0, it will be different following a restart so remove and re-add the server with the new port here
            try {
                server.removeServerProcess();
                server.addServerProcess();
            } catch (IOException e) {
                log.errorf("Error removing and adding process %s", downServerName);
                return;
            }
            try {
                server.startServerProcess();
            } catch (IOException e) {
                // AutoGenerated
                throw new RuntimeException(e);
            }

        } else {
            server.setState(ServerState.FAILED);
            respawn(server);
        }
    }

    private void launchProcessManagerSlave() {
        this.processManagerSlave = ProcessManagerSlaveFactory.getInstance().getProcessManagerSlave(environment, getHostModel(), processManagerCommmandHandler);
        Thread t = new Thread(this.processManagerSlave.getController(), "Server Manager Process");
        t.start();
    }

    private void launchDirectServerCommunicationHandler() {
        try {
            this.directServerCommunicationListener = DirectServerCommunicationListener.create(serverCommandHandler, this, environment.getServerManagerAddress(), environment.getServerManagerPort(), 20);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void activateLocalDomainController(final ServiceActivatorContext serviceActivatorContext) {
        try {
            final BatchBuilder batchBuilder = serviceActivatorContext.getBatchBuilder();

            final XMLMapper mapper = XMLMapper.Factory.create();
            extensionRegistrar.registerStandardDomainReaders(mapper);

            final DomainController domainController = new DomainController();


            batchBuilder.addService(DomainController.SERVICE_NAME, domainController)
                .addInjection(domainController.getXmlMapperInjector(), mapper)
                .addInjection(domainController.getDomainConfigDirInjector(), environment.getDomainConfigurationDir())
                .addDependency(SERVICE_NAME_BASE.append("executor"), ScheduledExecutorService.class, domainController.getScheduledExecutorServiceInjector());

            // TODO consider having all these as components of DomainController
            // and not independent services
            DomainDeploymentRepository.addService(environment.getDomainDeploymentDir(), batchBuilder);
            DomainDeploymentManagerImpl.addService(serviceContainer, batchBuilder);
            DomainConfigurationPersister.addService(batchBuilder);

            final DomainControllerOperationHandler domainControllerOperationHandler = new DomainControllerOperationHandler();
            batchBuilder.addService(DomainControllerOperationHandler.SERVICE_NAME, domainControllerOperationHandler)
                .addDependency(DomainController.SERVICE_NAME, DomainController.class, domainControllerOperationHandler.getDomainControllerInjector())
                .addDependency(SERVICE_NAME_BASE.append("executor"), ScheduledExecutorService.class, domainControllerOperationHandler.getExecutorServiceInjector())
                .addDependency(DomainDeploymentManager.SERVICE_NAME_LOCAL, DomainDeploymentManager.class, domainControllerOperationHandler.getDomainDeploymentManagerInjector())
                .addDependency(DomainDeploymentRepository.SERVICE_NAME, DomainDeploymentRepository.class, domainControllerOperationHandler.getDomainDeploymentRepositoryInjector())
                .addInjection(domainControllerOperationHandler.getLocalFileRepositoryInjector(), fileRepository)
                .addDependency(ManagementCommunicationService.SERVICE_NAME, ManagementCommunicationService.class, new ManagementCommunicationServiceInjector(domainControllerOperationHandler));

            batchBuilder.addService(SERVICE_NAME_BASE.append("local", "dc", "connection"), Service.NULL)
                .addDependency(DomainController.SERVICE_NAME, DomainController.class, new Injector<DomainController>(){
                    public void inject(DomainController value) throws InjectionException {
                        setDomainControllerConnection(new LocalDomainControllerConnection(ServerManager.this, domainController, fileRepository));
                    }
                    public void uninject() {
                        setDomainControllerConnection(null);
                    }
                });

        } catch (Exception e) {
            throw new RuntimeException("Exception starting local domain controller", e);
        }
    }

    private void activateRemoteDomainControllerConnection(final ServiceActivatorContext serviceActivatorContext) {
        final BatchBuilder batchBuilder = serviceActivatorContext.getBatchBuilder();

        final DomainControllerConnectionService domainControllerClientService = new DomainControllerConnectionService(this, fileRepository, 20, 15L, 10L);
        final BatchServiceBuilder<Void> serviceBuilder = batchBuilder.addService(DomainControllerConnectionService.SERVICE_NAME, domainControllerClientService)
            .addListener(new AbstractServiceListener<Void>() {
                @Override
                public void serviceFailed(ServiceController<? extends Void> serviceController, StartException reason) {
                    log.error("Failed to register with domain controller.", reason);
                }
            })
            .setInitialMode(ServiceController.Mode.IMMEDIATE);

        HostModel hostConfig = getHostModel();
        final RemoteDomainControllerElement remoteDomainControllerElement = hostConfig.getRemoteDomainControllerElement();
        final InetAddress hostAddress;
        try {
            hostAddress = InetAddress.getByName(remoteDomainControllerElement.getHost());
        } catch (UnknownHostException e) {
            throw new RuntimeException("Failed to get remote domain controller address", e);
        }
        serviceBuilder.addInjection(domainControllerClientService.getDomainControllerAddressInjector(), hostAddress);
        serviceBuilder.addInjection(domainControllerClientService.getDomainControllerPortInjector(), remoteDomainControllerElement.getPort());

        final ManagementElement managementElement = hostConfig.getManagementElement();
        serviceBuilder.addDependency(NetworkInterfaceService.JBOSS_NETWORK_INTERFACE.append(managementElement.getInterfaceName()), NetworkInterfaceBinding.class, domainControllerClientService.getLocalManagementInterfaceInjector());
        serviceBuilder.addInjection(domainControllerClientService.getLocalManagementPortInjector(), managementElement.getPort());
        serviceBuilder.addDependency(SERVICE_NAME_BASE.append("executor"), ScheduledExecutorService.class, domainControllerClientService.getExecutorServiceInjector());
    }

    private void activateManagementCommunication(final ServiceActivatorContext serviceActivatorContext) {
        final BatchBuilder batchBuilder = serviceActivatorContext.getBatchBuilder();

        HostModel hostConfig = getHostModel();
        final ManagementElement managementElement = hostConfig.getManagementElement();
        if(managementElement == null) {
            throw new IllegalStateException("null management configuration");
        }
        final Set<InterfaceElement> hostInterfaces = hostConfig.getInterfaces();
        if(hostInterfaces != null) {
            for(InterfaceElement interfaceElement : hostInterfaces) {
                if(interfaceElement.getName().equals(managementElement.getInterfaceName())) {
                    interfaceElement.activate(serviceActivatorContext);
                    break;
                }
            }
        }

        // Add the executor
        final ServiceName threadFactoryServiceName = SERVICE_NAME_BASE.append("thread-factory");
        batchBuilder.addService(threadFactoryServiceName, new ThreadFactoryService());
        final ServiceName executorServiceName = SERVICE_NAME_BASE.append("executor");

        /**
         * Replace below with fixed ScheduledThreadPoolService
         */
        final InjectedValue<ThreadFactory> threadFactoryValue = new InjectedValue<ThreadFactory>();
        batchBuilder.addService(executorServiceName, new Service<ScheduledExecutorService>() {
            private ScheduledExecutorService executorService;
            public synchronized void start(StartContext context) throws StartException {
                executorService = Executors.newScheduledThreadPool(20, threadFactoryValue.getValue());
            }

            public synchronized void stop(StopContext context) {
                executorService.shutdown();
            }

            public synchronized ScheduledExecutorService getValue() throws IllegalStateException {
                return executorService;
            }
        }).addDependency(threadFactoryServiceName, ThreadFactory.class, threadFactoryValue);

        //  Add the management communication service
        final ManagementCommunicationService managementCommunicationService = new ManagementCommunicationService();
        batchBuilder.addService(ManagementCommunicationService.SERVICE_NAME, managementCommunicationService)
            .addDependency(NetworkInterfaceService.JBOSS_NETWORK_INTERFACE.append(managementElement.getInterfaceName()), NetworkInterfaceBinding.class, managementCommunicationService.getInterfaceInjector())
            .addInjection(managementCommunicationService.getPortInjector(), managementElement.getPort())
            .addDependency(executorServiceName, ExecutorService.class, managementCommunicationService.getExecutorServiceInjector())
            .setInitialMode(ServiceController.Mode.IMMEDIATE);

        //  Add the server manager operation handler
        final ManagementOperationHandlerService<ServerManagerOperationHandler> operationHandlerService
                = new ManagementOperationHandlerService<ServerManagerOperationHandler>(new ServerManagerOperationHandler(this));
            batchBuilder.addService(ManagementCommunicationService.SERVICE_NAME.append("server", "manager"), operationHandlerService)
                .addDependency(ManagementCommunicationService.SERVICE_NAME, ManagementCommunicationService.class, new ManagementCommunicationServiceInjector(operationHandlerService));
    }

    void setDomainControllerConnection(final DomainControllerConnection domainControllerConnection) {
        this.domainControllerConnection = domainControllerConnection;
        if(domainControllerConnection == null) {
            return;
        }
        final DomainModel domainModel = domainControllerConnection.register();
        setDomain(domainModel);
    }

    public ModelManager getModelManager() {
        return modelManager;
    }

    protected DomainModel getDomainModel() {
        return modelManager.getDomainModel();
    }

    protected HostModel getHostModel() {
        return modelManager.getHostModel();
    }

    /**
     * Set the domain for the server manager.  If this is the first time the domain has been set on this instance it will
     * also invoke the server launch process.
     *
     * @param domain The domain configuration
     */
    public void setDomain(final DomainModel domain) {
        modelManager.setDomainModel(domain);
        synchronizeDeployments();
        if(serversStarted.compareAndSet(false, true)) {
            if (!environment.isRestart()) {
                startServers();
            } else {
                // FIXME -- this got dropped in the move to an update-based boot
                // handle it properly
//                reconnectServers();
            }
        }
    }

    Server getServer(String name) {
        return servers.get(name);
    }

    private void checkState(Server server, ServerState expected) {
        ServerState state = server.getState();
        if (state != expected) {
            log.warnf("Server %s is not in the expected %s state: %s" , server.getServerProcessName(), expected, state);
        }
    }

    public Map<String, Server> getServers() {
        synchronized (servers) {
            return Collections.unmodifiableMap(servers);
        }
    }

    DirectServerCommunicationListener getDirectServerCommunicationListener() {
        return directServerCommunicationListener;
    }

    private void synchronizeDeployments() {
        FileRepository remoteRepo = domainControllerConnection.getRemoteFileRepository();
        DomainModel domainConfig = getDomainModel();
        Set<String> serverGroupNames = domainConfig.getServerGroupNames();
        for (ServerElement server : getHostModel().getServers()) {
            String serverGroupName = server.getServerGroup();
            if (serverGroupNames.remove(serverGroupName)) {
                for (ServerGroupDeploymentElement deployment : domainConfig.getServerGroup(serverGroupName).getDeployments()) {
                    File[] local = fileRepository.getDeploymentFiles(deployment.getSha1Hash());
                    if (local == null || local.length == 0) {
                        remoteRepo.getDeploymentFiles(deployment.getSha1Hash());
                    }
                }
            }
        }
    }


    private void startServers() {
        InetSocketAddress managementSocket = new InetSocketAddress(directServerCommunicationListener.getSmAddress(), directServerCommunicationListener.getSmPort());
        HostModel hostConfig = getHostModel();
        DomainModel domainConfig = getDomainModel();
        for (ServerElement serverEl : hostConfig.getServers()) {
            // TODO take command line input on what servers to start
            if (serverEl.isStart()) {
                log.info("Starting server " + serverEl.getName());
                try {
                    Server server = new Server(serverEl.getName(), domainConfig, hostConfig, environment, processManagerSlave, managementSocket);
                    servers.put(server.getServerProcessName(), server);
                    // Now that the server is in the servers map we can start it
                    server.addServerProcess();
                    server.startServerProcess();
                } catch (IOException e) {
                    // FIXME handle failure to start server
                    log.error("Failed to start server " + serverEl.getName(), e);
                }
            }
            else log.info("Server " + serverEl.getName() + " is configured to not be started");
        }
    }

    /**
     * Callback for the {@link ServerToServerManagerProtocolCommand#handleCommand(String, ServerToServerManagerCommandHandler, Command)} calls
     */
    private class ServerCommandHandler extends ServerToServerManagerCommandHandler{

        @Override
        public void handleServerAvailable(String sourceProcessName) {
            ServerManager.this.availableServer(sourceProcessName);
        }

        @Override
        public void handleServerReconnectStatus(String sourceProcessName, ServerState state) {
            ServerManager.this.reconnectedServer(sourceProcessName, state);
        }

        @Override
        public void handleServerStartFailed(String sourceProcessName) {
            ServerManager.this.failedStartServer(sourceProcessName);
        }

        @Override
        public void handleServerStarted(String sourceProcessName) {
            ServerManager.this.startedServer(sourceProcessName);
        }

        @Override
        public void handleServerStopped(String sourceProcessName) {
            ServerManager.this.stoppedServer(sourceProcessName);
        }
    }

    private class ProcessManagerCommandHandler implements OutgoingPmCommandHandler {
        @Override
        public void handleShutdown() {
            ServerManager.this.stop();

            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    SystemExiter.exit(0);
                }
            }, "Server Manager Exit Thread");
            t.start();
        }

        @Override
        public void handleReconnectServerManager(String addr, String port) {
            log.warn("Wrong command received " + OutgoingPmCommand.RECONNECT_SERVER_MANAGER + " for server manager");
        }

        @Override
        public void handleDown(String serverName) {
            ServerManager.this.downServer(serverName);
        }
    }

}
