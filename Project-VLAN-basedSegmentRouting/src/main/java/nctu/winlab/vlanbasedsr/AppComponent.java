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
import java.util.Properties;

import static org.onosproject.net.config.NetworkConfigEvent.Type.CONFIG_ADDED;
import static org.onosproject.net.config.NetworkConfigEvent.Type.CONFIG_UPDATED;
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

    private HashMap<IpPrefix, DeviceId> subnet_table = new HashMap<IpPrefix, DeviceId>();
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
                this.subnet_table.put(config.subnet().get(), device.id());
            }
        }

        // Initialize host flow rules
        for (Host host: hostService.getHosts()) {
            addHostFlowRules(host);
        }
        // Initialize segment routing rules
        for (Device device: deviceService.getDevices(Device.Type.SWITCH)) {
            addSegmentRoutingRules(device);
        }
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
            if (event.type() == CONFIG_ADDED || event.type() == CONFIG_UPDATED) {
                if (event.configClass().equals(SegmentRoutingConfig.class)) {
                    SegmentRoutingConfig config = (SegmentRoutingConfig)event.config().get();
                    DeviceId deviceid = config.subject();

                    Device device = deviceService.getDevice(deviceid);
                    if (device != null && device.type() == Device.Type.SWITCH) {
                        log.info("switch {} SR Config is updated!", deviceid);
                        log.info("\tID = {}, Subnet = {}", config.segmentId(), config.subnet());
                    }
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
                    addHostFlowRules(host);
                    break;
                case HOST_REMOVED:
                    removeHostFlowRules(host);
                    break;
                case HOST_MOVED:
                case HOST_UPDATED:
                    removeHostFlowRules(event.prevSubject());
                    addHostFlowRules(host);
                    break;
            }
        }
    }
}

