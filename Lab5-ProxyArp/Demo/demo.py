#!/usr/bin/env python

from mininet.topo import Topo
from mininet.net import Mininet
from mininet.cli import CLI
from mininet.node import RemoteController, Host, OVSSwitch
from mininet.link import TCLink
from mininet.log import setLogLevel, info, warn, error
import os

class Logger():

    def __init__(self):
        setLogLevel('info')

    def info(self, log):
        info('[INFO] %s\n' % log)

    def warn(self, log):
        warn('[WARN] %s\n' % log)

    def error(self, log):
        error('[ERROR] %s\n' % log)

logger = Logger()

class MyTopo(Topo):

    def __init__(self):

        logger.info('Creating Mininet topology...')

        Topo.__init__(self)

        h1 = self.addHost("h1")
        h2 = self.addHost("h2")
        h3 = self.addHost("h3")

        s1 = self.addSwitch("s1", protocols="OpenFlow14")
        s2 = self.addSwitch("s2", protocols="OpenFlow14")
        s3 = self.addSwitch("s3", protocols="OpenFlow14")
        s4 = self.addSwitch("s4", protocols="OpenFlow14")
        s5 = self.addSwitch("s5", protocols="OpenFlow14")
        s6 = self.addSwitch("s6", protocols="OpenFlow14")
        s7 = self.addSwitch("s7", protocols="OpenFlow14")
        s8 = self.addSwitch("s8", protocols="OpenFlow14")

        self.addLink(h1, s1)
        self.addLink(h2, s8)
        self.addLink(h3, s4)

        self.addLink(s1, s2)
        self.addLink(s2, s3)
        self.addLink(s3, s4)
        self.addLink(s1, s5)
        self.addLink(s2, s6)
        self.addLink(s3, s6)
        self.addLink(s3, s7)
        self.addLink(s4, s7)
        self.addLink(s5, s6)
        self.addLink(s6, s7)
        self.addLink(s5, s8)
        self.addLink(s6, s8)
        self.addLink(s7, s8)


topos = {'mytopo' : MyTopo}

if __name__ == '__main__':
    topo = MyTopo()
    net = Mininet(topo=topo, link=TCLink, controller=None)
    net.addController('c0', switch=OVSSwitch, controller=RemoteController, ip='127.0.0.1')

    net.start()
    CLI(net)
    net.stop()
    os.system("mn -c")
