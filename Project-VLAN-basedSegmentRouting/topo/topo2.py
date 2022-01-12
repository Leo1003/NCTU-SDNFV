#!/usr/bin/python

from mininet.topo import Topo
from mininet.net import Mininet
from mininet.log import setLogLevel
from mininet.node import OVSSwitch, RemoteController
from mininet.cli import CLI
from mininet.node import Node
from mininet.link import TCLink

class MyTopo( Topo ):

    def __init__( self ):
        Topo.__init__( self )

        h11 = self.addHost('h11', ip='10.0.1.1/16', mac='ea:e9:78:fb:fd:01')
        h12 = self.addHost('h12', ip='10.0.1.2/16', mac='ea:e9:78:fb:fd:02')
        h21 = self.addHost('h21', ip='10.0.2.1/16', mac='ea:e9:78:fb:fd:03')
        h22 = self.addHost('h22', ip='10.0.2.2/16', mac='ea:e9:78:fb:fd:04')
        h23 = self.addHost('h23', ip='0.0.0.0', mac='ea:e9:78:fb:fd:05')
        h24 = self.addHost('h24', ip='0.0.0.0', mac='ea:e9:78:fb:fd:06')
        h31 = self.addHost('h31', ip='10.0.3.1/16', mac='ea:e9:78:fb:fd:07')
        h32 = self.addHost('h32', ip='10.0.3.2/16', mac='ea:e9:78:fb:fd:08')
        h33 = self.addHost('h33', ip='10.0.3.3/16', mac='ea:e9:78:fb:fd:09')
        h41 = self.addHost('h41', ip='10.0.4.1/16', mac='ea:e9:78:fb:fd:0a')
        h42 = self.addHost('h42', ip='10.0.4.2/16', mac='ea:e9:78:fb:fd:0b')
        h9 = self.addHost('h9', ip='10.0.9.1/16', mac='ea:e9:78:fb:ff:01')

        s1 = self.addSwitch('s1', protocols="OpenFlow14")
        s2 = self.addSwitch('s2', protocols="OpenFlow14")
        s3 = self.addSwitch('s3', protocols="OpenFlow14")
        s4 = self.addSwitch('s4', protocols="OpenFlow14")
        s5 = self.addSwitch('s5', protocols="OpenFlow14")
        s6 = self.addSwitch('s6', protocols="OpenFlow14")
        s7 = self.addSwitch('s7', protocols="OpenFlow14")
        s8 = self.addSwitch('s8', protocols="OpenFlow14")
        s9 = self.addSwitch('s9', protocols="OpenFlow14")

        self.addLink(s1, h11)
        self.addLink(s1, h12)
        self.addLink(s2, h21)
        self.addLink(s2, h22)
        self.addLink(s2, h23)
        self.addLink(s2, h24)
        self.addLink(s3, h31)
        self.addLink(s3, h32)
        self.addLink(s3, h33)
        self.addLink(s4, h41)
        self.addLink(s4, h42)
        self.addLink(s9, h9)

        self.addLink(s1, s5)
        self.addLink(s1, s6)
        self.addLink(s2, s6)
        self.addLink(s2, s7)
        self.addLink(s3, s5)
        self.addLink(s3, s8)
        self.addLink(s4, s7)
        self.addLink(s4, s8)
        self.addLink(s5, s6)
        self.addLink(s6, s7)
        self.addLink(s7, s8)
        self.addLink(s8, s5)
        self.addLink(s9, s3)
        self.addLink(s9, s4)
        self.addLink(s9, s6)

def run():
    topo = MyTopo()
    net = Mininet(topo=topo, controller=None, link=TCLink)
    net.addController('c0', controller=RemoteController, ip='127.0.0.1', port=6653)

    net.start()

    print("[+] Run DHCP server")
    dhcp = net.getNodeByName('h21')
    # dhcp.cmdPrint('service isc-dhcp-server restart &')
    dhcp.cmdPrint('/usr/sbin/dhcpd 4 -pf /run/dhcp-server-dhcpd.pid -cf ./dhcpd.conf %s' % dhcp.defaultIntf())

    CLI(net)
    print("[-] Killing DHCP server")
    dhcp.cmdPrint("kill -9 `ps aux | grep h21-eth0 | grep dhcpd | awk '{print $2}'`")
    net.stop()

if __name__ == '__main__':
    setLogLevel('info')
    run()
