#!/usr/bin/python

from mininet.topo import Topo
from mininet.net import Mininet
from mininet.log import setLogLevel
from mininet.node import RemoteController
from mininet.cli import CLI
from mininet.node import Node
from mininet.link import TCLink

class MyTopo( Topo ):

    def __init__( self ):
        Topo.__init__( self )

        # h2 is DHCP server
        h2 = self.addHost('h2', ip='192.168.2.2/16', mac='ea:e9:78:fb:fd:02')
        
        # h1, h3 need to request IP addresses from DHCP server
        h1 = self.addHost('h1', ip='0.0.0.0/16', mac='ea:e9:78:fb:fd:01')
        h3 = self.addHost('h3', ip='0.0.0.0/16', mac='ea:e9:78:fb:fd:03')
        
        # h4, h5 are at another sunbet
        h4 = self.addHost('h4', ip='192.168.2.33/16', mac='ea:e9:78:fb:fd:04')
        h5 = self.addHost('h5', ip='192.168.2.34/16', mac='ea:e9:78:fb:fd:05')

        # h6, h7 are at another sunbet
        h6 = self.addHost('h6', ip='192.168.2.65/16', mac='ea:e9:78:fb:fd:06')
        h7 = self.addHost('h7', ip='192.168.2.66/16', mac='ea:e9:78:fb:fd:07')

        s1 = self.addSwitch('s1', protocols="OpenFlow14")
        s2 = self.addSwitch('s2', protocols="OpenFlow14")
        s3 = self.addSwitch('s3', protocols="OpenFlow14")
        s4 = self.addSwitch('s4', protocols="OpenFlow14")
        s5 = self.addSwitch('s5', protocols="OpenFlow14")

        self.addLink(s3, h1)
        self.addLink(s3, h2)
        self.addLink(s3, h3)
        self.addLink(s4, h4)
        self.addLink(s4, h5)
        self.addLink(s5, h6)
        self.addLink(s5, h7)

        self.addLink(s1, s3)
        self.addLink(s1, s4)
        self.addLink(s1, s5)
        self.addLink(s2, s3)
        self.addLink(s2, s4)
        self.addLink(s2, s5)


def run():
    topo = MyTopo()
    net = Mininet(topo=topo, controller=None, link=TCLink)
    net.addController('c0', controller=RemoteController, ip='127.0.0.1', port=6653)

    net.start()

    print("[+] Run DHCP server")
    dhcp = net.getNodeByName('h2')
    # dhcp.cmdPrint('service isc-dhcp-server restart &')
    dhcp.cmdPrint('/usr/sbin/dhcpd 4 -pf /run/dhcp-server-dhcpd.pid -cf ./dhcpd.conf %s' % dhcp.defaultIntf())

    CLI(net)
    print("[-] Killing DHCP server")
    dhcp.cmdPrint("kill -9 `ps aux | grep h2-eth0 | grep dhcpd | awk '{print $2}'`")
    net.stop()

if __name__ == '__main__':
    setLogLevel('info')
    run()

