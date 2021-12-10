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
package nctu.winlab.bridge;

import org.onlab.packet.Ethernet;
import org.onlab.packet.ICMP;
import org.onlab.packet.ICMP6;
import org.onlab.packet.IPv4;
import org.onlab.packet.IPv6;
import org.onlab.packet.Ip4Prefix;
import org.onlab.packet.Ip6Prefix;
import org.onlab.packet.MacAddress;
import org.onlab.packet.TCP;
import org.onlab.packet.TpPort;
import org.onlab.packet.UDP;
import org.onlab.packet.VlanId;
import org.onosproject.cfg.ComponentConfigService;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.event.Event;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.Device;
import org.onosproject.net.DeviceId;
import org.onosproject.net.Host;
import org.onosproject.net.HostId;
import org.onosproject.net.Link;
import org.onosproject.net.Path;
import org.onosproject.net.PortNumber;
import org.onosproject.net.device.DeviceEvent;
import org.onosproject.net.device.DeviceListener;
import org.onosproject.net.device.DeviceService;
import org.onosproject.net.flow.DefaultFlowRule;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.FlowEntry;
import org.onosproject.net.flow.FlowRule;
import org.onosproject.net.flow.FlowRuleService;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.flow.criteria.Criterion;
import org.onosproject.net.flow.criteria.EthCriterion;
import org.onosproject.net.flow.instructions.Instruction;
import org.onosproject.net.flow.instructions.Instructions;
import org.onosproject.net.flowobjective.DefaultForwardingObjective;
import org.onosproject.net.flowobjective.FlowObjectiveService;
import org.onosproject.net.flowobjective.ForwardingObjective;
import org.onosproject.net.host.HostService;
import org.onosproject.net.link.LinkEvent;
import org.onosproject.net.packet.InboundPacket;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.PacketPriority;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketService;
import org.onosproject.net.topology.TopologyEvent;
import org.onosproject.net.topology.TopologyListener;
import org.onosproject.net.topology.TopologyService;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Dictionary;
import java.util.HashMap;
import java.util.Properties;

import static org.onlab.util.Tools.get;

/**
 * Skeletal ONOS application component.
 */
@Component(immediate = true,
           service = {AppComponent.class},
           property = {
           })
public class AppComponent {
    /* Runtime data */
    private final Logger log = LoggerFactory.getLogger(getClass());

    private ApplicationId appId;

    private BridgePacketProcessor processor = new BridgePacketProcessor();

    private SwitchBaseFlowRulesListener switchFlowRulesListener = new SwitchBaseFlowRulesListener();

    private HashMap<DeviceId, HashMap<MacAddress, PortNumber>> addr_table = new HashMap<DeviceId, HashMap<MacAddress, PortNumber>>();

    /* Referenced services */
    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected ComponentConfigService cfgService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected DeviceService deviceService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected FlowRuleService flowRuleService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected FlowObjectiveService flowObjectiveService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected PacketService packetService;

    /* Application Entries */

    @Activate
    protected void activate() {
        //cfgService.registerProperties(getClass());
        this.appId = coreService.registerApplication("nctu.winlab.bridge");
        packetService.addProcessor(this.processor, PacketProcessor.director(2));
        initBaseFlowRules();
        deviceService.addListener(switchFlowRulesListener);

        log.info("Activated");
    }

    @Deactivate
    protected void deactivate() {
        //cfgService.unregisterProperties(getClass(), false);
        deviceService.removeListener(switchFlowRulesListener);
        flowRuleService.removeFlowRulesById(appId);
        packetService.removeProcessor(processor);
        switchFlowRulesListener = null;
        processor = null;

        log.info("Deactivated");
    }

    @Modified
    public void modified(ComponentContext context) {
        Dictionary<?, ?> properties = context != null ? context.getProperties() : new Properties();
        if (context != null) {
        }
        log.info("Reconfigured");
    }

    /* Functions */
    private void initBaseFlowRules() {
        for (Device device: deviceService.getDevices(Device.Type.SWITCH)) {
            installBaseFlowRules(device.id());
        }
    }

    private void installBaseFlowRules(DeviceId id) {
        FlowRule ipv4_rule = construct_ipv4_rule(id);
        flowRuleService.applyFlowRules(ipv4_rule);
        log.info(String.format("Applied base flow rules on device %s", id));
    }

    private void uninstallBaseFlowRules(DeviceId id) {
        FlowRule ipv4_rule = construct_ipv4_rule(id);
        flowRuleService.removeFlowRules(ipv4_rule);
        log.info(String.format("Removed base flow rules on device %s", id));
    }

    private boolean checkMacTable(DeviceId deviceid, MacAddress mac) {
        HashMap<MacAddress, PortNumber> device_table = this.addr_table.get(deviceid);
        if (device_table == null) {
            return false;
        }
        return device_table.containsKey(mac);
    }

    private PortNumber searchMacTable(DeviceId deviceid, MacAddress mac) {
        HashMap<MacAddress, PortNumber> device_table = this.addr_table.get(deviceid);
        if (device_table == null) {
            return null;
        }
        return device_table.get(mac);
    }

    private void insertMacTable(DeviceId deviceid, MacAddress mac, long port) {
        insertMacTable(deviceid, mac, PortNumber.portNumber(port));
    }

    private void insertMacTable(DeviceId deviceid, MacAddress mac, PortNumber port) {
        if (!this.addr_table.containsKey(deviceid)) {
            this.addr_table.put(deviceid, new HashMap<MacAddress, PortNumber>());
        }
        HashMap<MacAddress, PortNumber> device_table = this.addr_table.get(deviceid);
        device_table.put(mac, port);
    }

    private FlowRule construct_ipv4_rule(DeviceId id) {
        TrafficTreatment packet_in_traffic = DefaultTrafficTreatment.builder()
            .setOutput(PortNumber.CONTROLLER)
            .build();
        TrafficSelector ipv4_selector = DefaultTrafficSelector.builder()
            .matchEthType(Ethernet.TYPE_IPV4)
            .build();
        FlowRule ipv4_rule = DefaultFlowRule.builder()
            .fromApp(this.appId)
            .forDevice(id)
            .withSelector(ipv4_selector)
            .withTreatment(packet_in_traffic)
            .withPriority(5)
            .makePermanent()
            .build();
        return ipv4_rule;
    }

    private FlowRule construct_output_rule(DeviceId id, MacAddress src, MacAddress dst, PortNumber port) {
        TrafficTreatment traffic = DefaultTrafficTreatment.builder()
            .setOutput(port)
            .build();
        TrafficSelector selector = DefaultTrafficSelector.builder()
            .matchEthSrc(src)
            .matchEthDst(dst)
            .build();
        FlowRule rule = DefaultFlowRule.builder()
            .fromApp(this.appId)
            .forDevice(id)
            .withSelector(selector)
            .withTreatment(traffic)
            .withPriority(30)
            .makeTemporary(30)
            .build();
        return rule;
    }

    private ForwardingObjective construct_output_objective(DeviceId id, MacAddress src, MacAddress dst, PortNumber port) {
        TrafficTreatment traffic = DefaultTrafficTreatment.builder()
            .setOutput(port)
            .build();
        TrafficSelector selector = DefaultTrafficSelector.builder()
            .matchEthSrc(src)
            .matchEthDst(dst)
            .build();
		ForwardingObjective objective = DefaultForwardingObjective.builder()
			.fromApp(this.appId)
			.withFlag(ForwardingObjective.Flag.VERSATILE)
			.withSelector(selector)
			.withTreatment(traffic)
			.withPriority(30)
			.makeTemporary(30)
			.add();
        return objective;
    }

    /* Packet processor */
    private class BridgePacketProcessor implements PacketProcessor {
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

            // Skip control packet
            if (eth_type == Ethernet.TYPE_LLDP || eth_type == Ethernet.TYPE_BSN) {
                return;
            }
            // Skip LLDP MAC address
            if (dst_mac.isLldp()) {
                return;
            }
            // Skip multicast MAC address
            if (dst_mac.isMulticast()) {
                return;
            }

            PortNumber old_in_port = searchMacTable(deviceid, src_mac);
            if (old_in_port == null || old_in_port != in_port) {
                log.info(String.format("Add MAC address ==> switch: %s, MAC: %s, port: %s", deviceid, src_mac, in_port));
                insertMacTable(deviceid, src_mac, in_port);
            }

            PortNumber out_port = searchMacTable(deviceid, dst_mac);
            if (out_port == null) {
                log.info(String.format("MAC %s is missed on %s! Flood packet!", dst_mac, deviceid));
                context.treatmentBuilder().setOutput(PortNumber.FLOOD);
                context.send();
            } else {
                log.info(String.format("MAC %s is matched on %s! Install flow rule!", dst_mac, deviceid));
                ForwardingObjective objective = construct_output_objective(deviceid, src_mac, dst_mac, out_port);
                flowObjectiveService.forward(deviceid, objective);
                context.treatmentBuilder().setOutput(out_port);
                context.send();
            }
        }
    }

    /* Device Listener */
    private class SwitchBaseFlowRulesListener implements DeviceListener {
        @Override
        public void event(DeviceEvent event) {
            Device device = event.subject();
            if (device.type() == Device.Type.SWITCH) {
                if (event.type() == DeviceEvent.Type.DEVICE_ADDED) {
                    installBaseFlowRules(device.id());
                } else if (event.type() == DeviceEvent.Type.DEVICE_REMOVED) {
                    uninstallBaseFlowRules(device.id());
                    addr_table.remove(device.id());
                }
            }
        }
    }
}
