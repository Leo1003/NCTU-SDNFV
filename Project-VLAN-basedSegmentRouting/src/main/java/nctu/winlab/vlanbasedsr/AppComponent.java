/*
 * Copyright 2022-present Open Networking Foundation
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
package nctu.winlab.vlanbasedsr;

import org.onlab.packet.Ethernet;
import org.onlab.packet.IpAddress;
import org.onlab.packet.IpPrefix;
import org.onlab.packet.Ip4Prefix;
import org.onlab.packet.VlanId;
import org.onosproject.cfg.ComponentConfigService;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.net.Device;
import org.onosproject.net.DeviceId;
import org.onosproject.net.Host;
import org.onosproject.net.HostLocation;
import org.onosproject.net.Link;
import org.onosproject.net.config.ConfigFactory;
import org.onosproject.net.config.NetworkConfigEvent;
import org.onosproject.net.config.NetworkConfigListener;
import org.onosproject.net.config.NetworkConfigRegistry;
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
import org.onosproject.net.flowobjective.DefaultNextObjective;
import org.onosproject.net.flowobjective.FlowObjectiveService;
import org.onosproject.net.flowobjective.ForwardingObjective;
import org.onosproject.net.host.HostEvent;
import org.onosproject.net.host.HostListener;
import org.onosproject.net.host.HostService;
import org.onosproject.net.topology.Topology;
import org.onosproject.net.topology.TopologyGraph;
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
import java.util.Map;
import java.util.Properties;

import static org.onosproject.net.config.basics.SubjectFactories.DEVICE_SUBJECT_FACTORY;

@Component(immediate = true)
public class AppComponent {
    private final int MAIN_PRIORITY = 2000;
    private final int SEGMENT_ROUTING_PRIORITY = 2000;
    private final int HOST_FORWARDING_PRIORITY = 2000;

    private final int MAIN_TABLEID = 0;
    private final int SEGMENT_ROUTING_TABLEID = 1;
    private final int HOST_FORWARDING_TABLEID = 2;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected NetworkConfigRegistry cfgService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected DeviceService deviceService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected FlowRuleService flowRuleService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected FlowObjectiveService flowObjectiveService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected HostService hostService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected TopologyService topologyService;

    private ApplicationId appId;

    private final Logger log = LoggerFactory.getLogger(getClass());

    private final SRConfigListener cfgListener = new SRConfigListener();

    private final SRHostListener hostListener = new SRHostListener();

    private HashMap<IpPrefix, Short> subnet_table = new HashMap<IpPrefix, Short>();
    private HashMap<Short, DeviceId> segment_table = new HashMap<Short, DeviceId>();

    private final ConfigFactory<DeviceId, SegmentRoutingConfig> factory = new ConfigFactory<DeviceId, SegmentRoutingConfig>(
            DEVICE_SUBJECT_FACTORY, SegmentRoutingConfig.class, "SegmentRoutingConfig"
    ) {
        @Override
        public SegmentRoutingConfig createConfig() {
            return new SegmentRoutingConfig();
        }
    };

    @Activate
    protected void activate() {
        this.appId = coreService.registerApplication("nctu.winlab.vlanbasedsr");
        cfgService.addListener(cfgListener);
        cfgService.registerConfigFactory(factory);
        hostService.addListener(hostListener);

        rebuildFlowRules();
        log.info("Started");
    }

    @Deactivate
    protected void deactivate() {
        hostService.removeListener(hostListener);
        cfgService.unregisterConfigFactory(factory);
        cfgService.removeListener(cfgListener);

        flowRuleService.removeFlowRulesById(this.appId);
        log.info("Stopped");
    }

    private void rebuildFlowRules() {
        this.subnet_table.clear();
        this.segment_table.clear();
        flowRuleService.removeFlowRulesById(this.appId);

        // Create table mapping
        for (Device device: deviceService.getDevices(Device.Type.SWITCH)) {
            SegmentRoutingConfig config = cfgService.getConfig(device.id(), SegmentRoutingConfig.class);
            if (config == null) {
                continue;
            }

            this.segment_table.put(config.segmentId(), device.id());
            if (config.subnet().isPresent()) {
                this.subnet_table.put(config.subnet().get(), config.segmentId());
            }
        }

        // Initialize host flow rules
        for (Host host: hostService.getHosts()) {
            addHostFlowRules(host);
        }
        // Initialize switch rules
        for (Device device: deviceService.getDevices(Device.Type.SWITCH)) {
            addSegmentRoutingRules(device);
            addMainRules(device);
        }
        // Initialize vlan rules
        for (Map.Entry<IpPrefix, Short> entry: this.subnet_table.entrySet()) {
            addSubnetRules(entry.getKey(), entry.getValue());
        }
    }

    /*
     * Main Rules
     */
    private void addSubnetRules(IpPrefix subnet, short segmentId) {
        for (Device device: deviceService.getDevices(Device.Type.SWITCH)) {
            FlowRule nonlocal_rule = buildNonlocalFlowRule(device, subnet, segmentId);
            flowRuleService.applyFlowRules(nonlocal_rule);
        }
    }

    private void removeSubnetRules(IpPrefix subnet, short segmentId) {
        for (Device device: deviceService.getDevices(Device.Type.SWITCH)) {
            FlowRule nonlocal_rule = buildNonlocalFlowRule(device, subnet, segmentId);
            flowRuleService.removeFlowRules(nonlocal_rule);
        }
    }

    private void addMainRules(Device device) {
        SegmentRoutingConfig config = cfgService.getConfig(device.id(), SegmentRoutingConfig.class);

        FlowRule nondest_rule = buildNondestFlowRule(device);
        flowRuleService.applyFlowRules(nondest_rule);

        if (config != null) {
            FlowRule dest_rule = buildRoutingDestFlowRule(device, config.segmentId());
            flowRuleService.applyFlowRules(dest_rule);

            if (config.subnet().isPresent()) {
                FlowRule local_rule = buildLocalFlowRule(device, config.subnet().get());
                flowRuleService.applyFlowRules(local_rule);
            }
        }
    }

    private void removeMainRules(Device device) {
        SegmentRoutingConfig config = cfgService.getConfig(device.id(), SegmentRoutingConfig.class);
        removeMainRules(device, config);
    }

    private void removeMainRules(Device device, SegmentRoutingConfig config) {
        FlowRule nondest_rule = buildNondestFlowRule(device);
        flowRuleService.removeFlowRules(nondest_rule);

        if (config != null) {
            FlowRule dest_rule = buildRoutingDestFlowRule(device, config.segmentId());
            flowRuleService.removeFlowRules(dest_rule);

            if (config.subnet().isPresent()) {
                FlowRule local_rule = buildLocalFlowRule(device, config.subnet().get());
                flowRuleService.removeFlowRules(local_rule);
            }
        }
    }

    private FlowRule buildRoutingDestFlowRule(Device device, short segment) {
        TrafficSelector selector = DefaultTrafficSelector.builder()
            .matchVlanId(VlanId.vlanId(segment))
            .build();
        TrafficTreatment treatment = DefaultTrafficTreatment.builder()
            .immediate()
            .popVlan()
            .transition(this.HOST_FORWARDING_TABLEID)
            .build();
        return DefaultFlowRule.builder()
            .forDevice(device.id())
            .forTable(this.MAIN_TABLEID)
            .fromApp(this.appId)
            .withSelector(selector)
            .withTreatment(treatment)
            .withPriority(MAIN_PRIORITY + 15)
            .makePermanent()
            .build();
    }

    private FlowRule buildNondestFlowRule(Device device) {
        TrafficSelector selector = DefaultTrafficSelector.builder()
            .matchVlanId(VlanId.ANY)
            .build();
        TrafficTreatment treatment = DefaultTrafficTreatment.builder()
            .immediate()
            .transition(this.SEGMENT_ROUTING_TABLEID)
            .build();
        return DefaultFlowRule.builder()
            .forDevice(device.id())
            .forTable(this.MAIN_TABLEID)
            .fromApp(this.appId)
            .withSelector(selector)
            .withTreatment(treatment)
            .withPriority(MAIN_PRIORITY + 10)
            .makePermanent()
            .build();
    }

    private FlowRule buildLocalFlowRule(Device device, IpPrefix subnet) {
        TrafficSelector selector = DefaultTrafficSelector.builder()
            .matchEthType(Ethernet.TYPE_IPV4)
            .matchVlanId(VlanId.NONE)
            .matchIPDst(subnet)
            .build();
        TrafficTreatment treatment = DefaultTrafficTreatment.builder()
            .immediate()
            .transition(this.HOST_FORWARDING_TABLEID)
            .build();
        return DefaultFlowRule.builder()
            .forDevice(device.id())
            .forTable(this.MAIN_TABLEID)
            .fromApp(this.appId)
            .withSelector(selector)
            .withTreatment(treatment)
            .withPriority(MAIN_PRIORITY + 5)
            .makePermanent()
            .build();
    }

    private FlowRule buildNonlocalFlowRule(Device device, IpPrefix subnet, short segmentId) {
        TrafficSelector selector = DefaultTrafficSelector.builder()
            .matchEthType(Ethernet.TYPE_IPV4)
            .matchVlanId(VlanId.NONE)
            .matchIPDst(subnet)
            .build();
        TrafficTreatment treatment = DefaultTrafficTreatment.builder()
            .immediate()
            .pushVlan()
            .setVlanId(VlanId.vlanId(segmentId))
            .transition(this.SEGMENT_ROUTING_TABLEID)
            .build();
        return DefaultFlowRule.builder()
            .forDevice(device.id())
            .forTable(this.MAIN_TABLEID)
            .fromApp(this.appId)
            .withSelector(selector)
            .withTreatment(treatment)
            .withPriority(MAIN_PRIORITY)
            .makePermanent()
            .build();
    }

    /*
     * Switch Routing Rules
     */
    private void addSegmentRoutingRules(Device device) {
        Topology topo = topologyService.currentTopology();
        TopologyGraph graph = topologyService.getGraph(topo);
        SegmentRoutingConfig config = cfgService.getConfig(device.id(), SegmentRoutingConfig.class);
        if (config == null) {
            return;
        }

        for (Link l: new BfsLinkIterator(graph, device.id())) {
            FlowRule rule = buildRoutingFlowRule(l, config.segmentId());
            flowRuleService.applyFlowRules(rule);
        }
    }

    private void removeSegmentRoutingRules(Device device, SegmentRoutingConfig old_config) {
        removeSegmentRoutingRules(device, old_config.segmentId());
    }

    private void removeSegmentRoutingRules(Device device, short segmentId) {
        Topology topo = topologyService.currentTopology();
        TopologyGraph graph = topologyService.getGraph(topo);

        FlowRule dest_rule = buildRoutingDestFlowRule(device, segmentId);
        flowRuleService.removeFlowRules(dest_rule);
        for (Link l: new BfsLinkIterator(graph, device.id())) {
            FlowRule rule = buildRoutingFlowRule(l, segmentId);
            flowRuleService.removeFlowRules(rule);
        }
    }

    private FlowRule buildRoutingFlowRule(Link link, short segment) {
        TrafficSelector selector = DefaultTrafficSelector.builder()
            .matchVlanId(VlanId.vlanId(segment))
            .build();
        TrafficTreatment treatment = DefaultTrafficTreatment.builder()
            .setOutput(link.src().port())
            .build();
        return DefaultFlowRule.builder()
            .forDevice(link.src().deviceId())
            .forTable(this.SEGMENT_ROUTING_TABLEID)
            .fromApp(this.appId)
            .withSelector(selector)
            .withTreatment(treatment)
            .withPriority(SEGMENT_ROUTING_PRIORITY)
            .makePermanent()
            .build();
    }

    /*
     * Host Forwarding Rules
     */
    private void addHostFlowRules(Host host) {
        for (HostLocation location: host.locations()) {
            DeviceId deviceid = location.deviceId();
            SegmentRoutingConfig config = cfgService.getConfig(deviceid, SegmentRoutingConfig.class);
            if (config.subnet().isPresent()) {
                IpPrefix prefix = config.subnet().get();
                for (IpAddress ip: host.ipAddresses()) {
                    if (prefix.contains(ip)) {
                        FlowRule rule = buildHostFlowRule(location, ip);
                        flowRuleService.applyFlowRules(rule);
                    }
                }
            }
        }
    }

    private void rebuildHostFlowRules(Device device) {
        // Remove all entries in Host Forwarding Table
        for (FlowEntry entry: flowRuleService.getFlowEntries(device.id())) {
            if (entry.table().equals(this.HOST_FORWARDING_TABLEID) && entry.appId() == this.appId.id()) {
                flowRuleService.removeFlowRules(entry);
            }
        }

        for (Host host: hostService.getConnectedHosts(device.id())) {
            addHostFlowRules(host);
        }
    }

    private void removeHostFlowRules(Host host) {
        for (HostLocation location: host.locations()) {
            DeviceId deviceid = location.deviceId();
            SegmentRoutingConfig config = cfgService.getConfig(deviceid, SegmentRoutingConfig.class);
            if (config.subnet().isPresent()) {
                IpPrefix prefix = config.subnet().get();
                for (IpAddress ip: host.ipAddresses()) {
                    if (prefix.contains(ip)) {
                        FlowRule rule = buildHostFlowRule(location, ip);
                        flowRuleService.removeFlowRules(rule);
                    }
                }
            }
        }
    }

    private FlowRule buildHostFlowRule(HostLocation location, IpAddress ip) {
        TrafficSelector selector = DefaultTrafficSelector.builder()
            .matchEthType(Ethernet.TYPE_IPV4)
            .matchVlanId(VlanId.NONE)
            .matchIPDst(ip.toIpPrefix())
            .build();
        TrafficTreatment treatment = DefaultTrafficTreatment.builder()
            .setOutput(location.port())
            .build();
        return DefaultFlowRule.builder()
            .forDevice(location.deviceId())
            .forTable(this.HOST_FORWARDING_TABLEID)
            .fromApp(this.appId)
            .withSelector(selector)
            .withTreatment(treatment)
            .withPriority(HOST_FORWARDING_PRIORITY)
            .makePermanent()
            .build();
    }

    /*
     * Listeners
     */
    private class SRConfigListener implements NetworkConfigListener {
        @Override
        public void event(NetworkConfigEvent event) {
            if (event.configClass().equals(SegmentRoutingConfig.class)) {
                SegmentRoutingConfig config = (SegmentRoutingConfig)event.config().get();
                DeviceId deviceid = config.subject();
                Device device = deviceService.getDevice(deviceid);
                if (device == null) {
                    log.warn("Received strange config subject: <DeviceId = {}>", deviceid);
                    return;
                }
                if (device.type() != Device.Type.SWITCH) {
                    log.warn("Received config subject but is not switch: <DeviceId = {}, Type = {}>", deviceid, device.type());
                    return;
                }

                switch (event.type()) {
                    case CONFIG_ADDED:
                        log.info("switch {} SR Config is added!", deviceid);
                        log.info("\tID = {}, Subnet = {}", config.segmentId(), config.subnet());

                        segment_table.put(config.segmentId(), device.id());
                        addSegmentRoutingRules(device);
                        addMainRules(device);
                        if (config.subnet().isPresent()) {
                            subnet_table.put(config.subnet().get(), config.segmentId());
                            addSubnetRules(config.subnet().get(), config.segmentId());
                        }
                        break;
                    case CONFIG_UPDATED:
                        SegmentRoutingConfig prev_config = (SegmentRoutingConfig)event.prevConfig().get();

                        log.info("Switch {} SR Config is updated!", deviceid);
                        log.info("\tID = {} -> {}, Subnet = {} -> {}", 
                                prev_config.segmentId(), 
                                config.segmentId(), 
                                prev_config.subnet(), 
                                config.subnet());

                        // Detect if segment ID is modified
                        if (prev_config.segmentId() != config.segmentId()) {
                            log.info("Update segment routing rules");
                            removeSegmentRoutingRules(device, prev_config.segmentId());
                            removeMainRules(device);
                            segment_table.remove(prev_config.segmentId());

                            segment_table.put(config.segmentId(), device.id());
                            addSegmentRoutingRules(device);
                            addMainRules(device);
                        }
                        // Detect if subnet is modified
                        if (!prev_config.subnet().equals(config.subnet())) {
                            log.info("Update subnet forwarding rules");
                            if (prev_config.subnet().isPresent()) {
                                IpPrefix prev_subnet = prev_config.subnet().get();
                                Short prev_segmentId = subnet_table.get(prev_subnet);
                                if (prev_segmentId != null) {
                                    removeSubnetRules(prev_subnet, prev_segmentId);
                                    subnet_table.remove(prev_subnet);
                                } else {
                                    log.warn("Found previous subnet config not in table");
                                }
                            }
                            if (config.subnet().isPresent()) {
                                IpPrefix subnet = config.subnet().get();
                                subnet_table.put(config.subnet().get(), config.segmentId());
                                addSubnetRules(config.subnet().get(), config.segmentId());
                            }
                            rebuildHostFlowRules(device);
                        }
                        break;
                    case CONFIG_REMOVED:
                        log.info("switch {} SR Config is removed!", deviceid);
                        log.info("\tID = {}, Subnet = {}", config.segmentId(), config.subnet());

                        removeSegmentRoutingRules(device, config.segmentId());
                        segment_table.remove(config.segmentId());
                        removeMainRules(device);
                        if (config.subnet().isPresent()) {
                            IpPrefix prev_subnet = config.subnet().get();
                            Short prev_segmentId = subnet_table.get(prev_subnet);
                            if (prev_segmentId != null) {
                                removeSubnetRules(prev_subnet, prev_segmentId);
                                subnet_table.remove(prev_subnet);
                            } else {
                                log.warn("Found previous subnet config not in table");
                            }
                        }

                        addMainRules(device);
                        break;
                }
            }
        }
    }

    private class SRHostListener implements HostListener {
        @Override
        public void event(HostEvent event) {
            Host host = event.subject();
            switch (event.type()) {
                case HOST_ADDED:
                    log.info("Host {} added at: {}", host.ipAddresses(), host.location());
                    addHostFlowRules(host);
                    break;
                case HOST_REMOVED:
                    log.info("Host {} removed from: {}", host.ipAddresses(), host.location());
                    removeHostFlowRules(host);
                    break;
                case HOST_MOVED:
                case HOST_UPDATED:
                    log.info("Host {} updated to: {}", host.ipAddresses(), host.location());
                    removeHostFlowRules(event.prevSubject());
                    addHostFlowRules(host);
                    break;
            }
        }
    }
}

