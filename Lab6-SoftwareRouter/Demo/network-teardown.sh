#!/usr/bin/bash
podman network rm R1_network
podman network rm R2_network
podman network rm R3_network
podman network rm R1R2_br
podman network rm R2R3_br

