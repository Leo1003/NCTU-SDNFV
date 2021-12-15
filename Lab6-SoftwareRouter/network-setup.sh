#!/usr/bin/bash
podman network create --driver bridge --subnet 172.21.0.0/16 --gateway 172.21.0.254 --internal R1_network
podman network create --driver bridge --subnet 172.22.0.0/16 --gateway 172.22.0.254 --internal R2_network
podman network create --driver bridge --subnet 172.23.0.0/16 --gateway 172.23.0.254 --internal R3_network
podman network create --driver bridge --subnet 172.24.0.0/16 --gateway 172.24.0.254 --internal R4_network
podman network create --driver bridge --subnet 172.20.1.0/24 --gateway 172.20.1.254 --ip-range 172.20.1.0/30 --internal R1R2_br
podman network create --driver bridge --subnet 172.20.2.0/24 --gateway 172.20.2.254 --ip-range 172.20.2.0/30 --internal R2R3_br
podman network create --driver bridge --subnet 172.20.3.0/24 --gateway 172.20.3.254 --ip-range 172.20.3.0/30 --internal R3R4_br
podman network create --driver bridge --subnet 172.20.4.0/24 --gateway 172.20.4.254 --ip-range 172.20.4.0/30 --internal R4R1_br

