/*
 * Copyright 2021-present Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nctu.winlab.unicastdhcp;

import org.onlab.packet.Ethernet;
import org.onlab.packet.IPv4;
import org.onlab.packet.Ip4Prefix;
import org.onlab.packet.MacAddress;
import org.onlab.packet.TCP;
import org.onlab.packet.TpPort;
import org.onlab.packet.UDP;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.DefaultEdgeLink;
import org.onosproject.net.DeviceId;
import org.onosproject.net.EdgeLink;
import org.onosproject.net.ElementId;
import org.onosproject.net.Link;
import org.onosproject.net.Path;
import org.onosproject.net.PortNumber;
import org.onosproject.net.config.ConfigFactory;
import org.onosproject.net.config.NetworkConfigEvent;
import org.onosproject.net.config.NetworkConfigListener;
import org.onosproject.net.config.NetworkConfigRegistry;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.FlowRuleService;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.flowobjective.DefaultForwardingObjective;
import org.onosproject.net.flowobjective.FlowObjectiveService;
import org.onosproject.net.flowobjective.ForwardingObjective;
import org.onosproject.net.packet.InboundPacket;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.PacketPriority;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketService;
import org.onosproject.net.topology.PathService;
import org.onosproject.net.topology.TopologyService;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

import static org.onosproject.net.config.NetworkConfigEvent.Type.CONFIG_ADDED;
import static org.onosproject.net.config.NetworkConfigEvent.Type.CONFIG_UPDATED;
import static org.onosproject.net.config.basics.SubjectFactories.APP_SUBJECT_FACTORY;

/**
 * Skeletal ONOS application component.
 */
@Component(immediate = true)
public class AppComponent {

    private ApplicationId appId;

    private final Logger log = LoggerFactory.getLogger(getClass());

    private final NameConfigListener cfgListener = new NameConfigListener();

    private final ConfigFactory factory = new ConfigFactory<ApplicationId, NameConfig>(
            APP_SUBJECT_FACTORY, NameConfig.class, "UnicastDhcpConfig"
    ) {
        @Override
        public NameConfig createConfig() {
            return new NameConfig();
        }
    };

    private TrafficSelector dhcpSelector;

    private DhcpPacketProcessor processor = new DhcpPacketProcessor();

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected NetworkConfigRegistry cfgService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected FlowRuleService flowRuleService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected FlowObjectiveService flowObjectiveService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected PacketService packetService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected PathService pathService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected TopologyService topologyService;

    @Activate
    protected void activate() {
        this.appId = coreService.registerApplication("nctu.winlab.unicastdhcp");
        cfgService.addListener(cfgListener);
        cfgService.registerConfigFactory(factory);
        packetService.addProcessor(this.processor, PacketProcessor.director(2));
        installPacketInRules();
        log.info("Started");
    }

    @Deactivate
    protected void deactivate() {
        removePacketInRules();
        packetService.removeProcessor(processor);
        cfgService.removeListener(cfgListener);
        log.info("Stopped");
    }

    /**
     * Request packet in via packet service.
     */
    private void installPacketInRules() {
        this.dhcpSelector = DefaultTrafficSelector.builder()
                .matchEthType(Ethernet.TYPE_IPV4)
                .matchIPProtocol(IPv4.PROTOCOL_UDP)
                .matchIPSrc(Ip4Prefix.valueOf("0.0.0.0/32"))
                .matchIPDst(Ip4Prefix.valueOf("255.255.255.255/32"))
                .matchUdpSrc(TpPort.tpPort(68))
                .matchUdpDst(TpPort.tpPort(67))
                .build();
        packetService.requestPackets(this.dhcpSelector, PacketPriority.REACTIVE, appId);
    }

    /**
     * Cancel request for packet in via packet service.
     */
    private void removePacketInRules() {
        packetService.cancelPackets(this.dhcpSelector, PacketPriority.REACTIVE, appId);
    }

    private class NameConfigListener implements NetworkConfigListener {
        @Override
        public void event(NetworkConfigEvent event) {
            if (event.type() == CONFIG_ADDED || event.type() == CONFIG_UPDATED) {
                if (event.configClass().equals(NameConfig.class)) {
                    NameConfig config = cfgService.getConfig(appId, NameConfig.class);
                    if (config != null) {
                        log.info("DHCP server is at {}!", config.serverLocation());
                    }
                }
            }
        }
    }

    /* Packet processor */
    private class DhcpPacketProcessor implements PacketProcessor {
        @Override
        public void process(PacketContext context) {
            if (context.isHandled()) {
                return;
            }

            InboundPacket in_pkt = context.inPacket();
            Ethernet eth_pkt = in_pkt.parsed();
            if (eth_pkt == null) {
                return;
            }

            short eth_type = eth_pkt.getEtherType();
            MacAddress src_mac = eth_pkt.getSourceMAC();
            MacAddress dst_mac = eth_pkt.getDestinationMAC();
            PortNumber in_port = in_pkt.receivedFrom().port();
            DeviceId deviceid = in_pkt.receivedFrom().deviceId();

            // Skip non-IPv4 packets
            if (!(eth_type == Ethernet.TYPE_IPV4)) {
                return;
            }

            // Check if broadcast address
            if (!dst_mac.isBroadcast()) {
                return;
            }

            IPv4 ipv4_pkt = (IPv4)eth_pkt.getPayload();
            byte ipv4_proto = ipv4_pkt.getProtocol();
            if (!(ipv4_proto == IPv4.PROTOCOL_UDP)) {
                return;
            }

            UDP udp_pkt = (UDP)ipv4_pkt.getPayload();
            if (!(ipv4_pkt.getSourceAddress() == 0x00000000
                    && ipv4_pkt.getDestinationAddress() == 0xFFFFFFFF
                    && udp_pkt.getSourcePort() == 68
                    && udp_pkt.getDestinationPort() == 67)) {
                return;
            }

            // Consider this as a DHCPDISCOVER message

            // Load DHCP server configuration
            NameConfig config = cfgService.getConfig(appId, NameConfig.class);
            if (config == null) {
                log.warn("DHCPDISCOVER message received, but cannot find DHCP server config!");
                return;
            }
            ConnectPoint dhcpServerLoc = config.serverLocation();
            ElementId dhcpServer = dhcpServerLoc.elementId();

            // Build the selector and install flow rules
            TrafficSelector c2s_selector = DefaultTrafficSelector.builder()
                .matchEthSrc(eth_pkt.getSourceMAC())
                .matchEthType(Ethernet.TYPE_IPV4)
                .matchIPProtocol(IPv4.PROTOCOL_UDP)
                .matchUdpSrc(TpPort.tpPort(68))
                .matchUdpDst(TpPort.tpPort(67))
                .build();

            TrafficSelector s2c_selector = DefaultTrafficSelector.builder()
                .matchEthDst(eth_pkt.getSourceMAC())
                .matchEthType(Ethernet.TYPE_IPV4)
                .matchIPProtocol(IPv4.PROTOCOL_UDP)
                .matchUdpSrc(TpPort.tpPort(67))
                .matchUdpDst(TpPort.tpPort(68))
                .build();

            // Calculate the path forward and back
            if (!dhcpServer.equals(in_pkt.receivedFrom().elementId())) {
                log.info("Creating path between: {} <-> {}", dhcpServer, in_pkt.receivedFrom().elementId());
                Set<Path> paths_c2s = pathService.getPaths(in_pkt.receivedFrom().elementId(), dhcpServer);
                if (paths_c2s.isEmpty()) {
                    log.warn("There is no path to DHCP server from {}", in_pkt.receivedFrom());
                    return;
                }
                Path path_c2s = selectPath(paths_c2s, in_pkt.receivedFrom().port());
                if (path_c2s == null) {
                    log.warn("There is no normal path to DHCP server from {}", in_pkt.receivedFrom());
                    return;
                }

                Set<Path> paths_s2c = pathService.getPaths(dhcpServer, in_pkt.receivedFrom().elementId());
                if (paths_s2c.isEmpty()) {
                    log.warn("There is no path back to {} from DHCP server", in_pkt.receivedFrom());
                    return;
                }
                Path path_s2c = selectPath(paths_s2c, null);

                log.info("Install client to server path");
                installPathRules(c2s_selector, path_c2s);
                log.info("Install server to client path");
                installPathRules(s2c_selector, path_s2c);

                // Packet out
                context.treatmentBuilder().setOutput(path_c2s.src().port());
            } else {
                // DHCP Server and Client are on the same edge switch
                context.treatmentBuilder().setOutput(dhcpServerLoc.port());
            }

            log.info("Install to server edge path");
            installEdgeLink(c2s_selector, DefaultEdgeLink.createEdgeLink(dhcpServerLoc, false));
            log.info("Install to client edge path");
            installEdgeLink(s2c_selector, DefaultEdgeLink.createEdgeLink(in_pkt.receivedFrom(), false));

            context.send();
        }
    }

    // Selects a path that does not lead back to the specified port.
    private Path selectPath(Set<Path> paths, PortNumber notToPort) {
        for (Path path: paths) {
            if (!path.src().port().equals(notToPort)) {
                return path;
            }
        }
        return null;
    }

    private void installPathRules(TrafficSelector selector, Path path) {
        for (Link link: path.links()) {
            log.info("Install path rule: {} -> {}", link.src(), link.dst());

            TrafficTreatment treatment = DefaultTrafficTreatment.builder()
                    .setOutput(link.src().port())
                    .build();

            ForwardingObjective forwardingObjective = DefaultForwardingObjective.builder()
                .withSelector(selector)
                .withTreatment(treatment)
                .withPriority(60000)
                .withFlag(ForwardingObjective.Flag.VERSATILE)
                .fromApp(appId)
                .makeTemporary(30)
                .add();

            flowObjectiveService.forward(link.src().deviceId(), forwardingObjective);
        }
    }

    private void installEdgeLink(TrafficSelector selector, EdgeLink link) {
        log.info("Install edge link: {} -> DEST", link.src());

        TrafficTreatment treatment = DefaultTrafficTreatment.builder()
                .setOutput(link.src().port())
                .build();

        ForwardingObjective forwardingObjective = DefaultForwardingObjective.builder()
            .withSelector(selector)
            .withTreatment(treatment)
            .withPriority(60000)
            .withFlag(ForwardingObjective.Flag.VERSATILE)
            .fromApp(appId)
            .makeTemporary(30)
            .add();

        flowObjectiveService.forward(link.src().deviceId(), forwardingObjective);
    }
}

