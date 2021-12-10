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
package nctu.winlab.ProxyArp;

import org.onlab.packet.ARP;
import org.onlab.packet.Ethernet;
import org.onlab.packet.IPv4;
import org.onlab.packet.Ip4Prefix;
import org.onlab.packet.Ip4Address;
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
import org.onosproject.net.Host;
import org.onosproject.net.Link;
import org.onosproject.net.Path;
import org.onosproject.net.PortNumber;
import org.onosproject.net.edge.EdgePortService;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.host.HostService;
import org.onosproject.net.packet.DefaultOutboundPacket;
import org.onosproject.net.packet.InboundPacket;
import org.onosproject.net.packet.OutboundPacket;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.PacketPriority;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketService;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static org.onlab.util.Tools.get;

/**
 * Skeletal ONOS application component.
 */
@Component(immediate = true)
public class AppComponent {

    private ApplicationId appId;

    private final Logger log = LoggerFactory.getLogger(getClass());

    private ArpPacketProcessor processor = new ArpPacketProcessor();

    private Map<Ip4Address, MacAddress> arpTable = new HashMap<>();

    private Map<MacAddress, ConnectPoint> hostLocation = new HashMap<>();

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected EdgePortService edgePortService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected PacketService packetService;

    @Activate
    protected void activate() {
        this.appId = coreService.registerApplication("nctu.winlab.ProxyArp");
        packetService.addProcessor(this.processor, PacketProcessor.director(2));
        log.info("Started");
    }

    @Deactivate
    protected void deactivate() {
        packetService.removeProcessor(processor);
        log.info("Stopped");
    }

    private class ArpPacketProcessor implements PacketProcessor {
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
            PortNumber in_port = in_pkt.receivedFrom().port();
            DeviceId deviceid = in_pkt.receivedFrom().deviceId();

            // Skip non-IPv4 packets
            if (!(eth_type == Ethernet.TYPE_ARP)) {
                return;
            }

            ARP arp_pkt = (ARP)eth_pkt.getPayload();

            if (!(arp_pkt.getHardwareType() == ARP.HW_TYPE_ETHERNET && arp_pkt.getHardwareAddressLength() == 6)) {
                return;
            }

            if (!(arp_pkt.getProtocolType() == ARP.PROTO_TYPE_IP && arp_pkt.getProtocolAddressLength() == 4)) {
                return;
            }

            MacAddress src_mac = MacAddress.valueOf(arp_pkt.getSenderHardwareAddress());
            MacAddress dst_mac = MacAddress.valueOf(arp_pkt.getTargetHardwareAddress());
            Ip4Address src_ip = Ip4Address.valueOf(arp_pkt.getSenderProtocolAddress());
            Ip4Address dst_ip = Ip4Address.valueOf(arp_pkt.getTargetProtocolAddress());

            MacAddress orig_mac = arpTable.get(src_ip);
            if (orig_mac == null || !orig_mac.equals(src_mac)) {
                log.info("UPDATE TABLE. {}[{}] -> [{}]", src_ip, orig_mac, src_mac);
                arpTable.put(src_ip, src_mac);
            }

            if (arp_pkt.isGratuitous()) {
                // Gratuitous ARP is recorded only
                log.debug("Gratuitous ARP received.");
                context.block();
                return;
            }

            if (arp_pkt.getOpCode() == ARP.OP_REQUEST) {
                MacAddress target_mac = arpTable.get(dst_ip);
                if (target_mac == null) {
                    // Send to all edge ports
                    log.info("TABLE MISS. Send request to edge ports");
                    hostLocation.put(src_mac, in_pkt.receivedFrom());
                    packetOutEdgePorts(eth_pkt, in_pkt.receivedFrom());
                    context.block();
                } else {
                    // Directly reply
                    log.info("TABLE HIT. Requested MAC = {}", target_mac);

                    Ethernet resp = ARP.buildArpReply(dst_ip, target_mac, eth_pkt);
                    packetOut(resp, in_pkt.receivedFrom());

                    context.block();
                }
            } else if (arp_pkt.getOpCode() == ARP.OP_REPLY) {
                log.info("RECV PEPLY. Requested MAC = {}", src_mac);
                ConnectPoint cp = hostLocation.get(dst_mac);
                if (cp == null) {
                    log.warn("Received ARP_REPLY but no known connect point for {}", dst_mac);
                    return;
                }

                // Forward reply to the host
                packetOut(eth_pkt, cp);
                context.block();
            }
        }
    }

    private void packetOutEdgePorts(Ethernet pkt, ConnectPoint except) {
        for (ConnectPoint cp: edgePortService.getEdgePoints()) {
            if (cp.equals(except)) {
                continue;
            }

            packetOut(pkt, cp);
        }
    }

    private void packetOut(Ethernet pkt, ConnectPoint cp) {
        TrafficTreatment treatment = DefaultTrafficTreatment.builder()
            .setOutput(cp.port())
            .build();
        OutboundPacket out_pkt = new DefaultOutboundPacket(cp.deviceId(), treatment, ByteBuffer.wrap(pkt.serialize()));
        packetService.emit(out_pkt);
    }
}

