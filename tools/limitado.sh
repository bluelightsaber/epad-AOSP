#!/bin/sh
# Ejecuta un comando con recursos limitados (bilbi tiene otros procesos pesados del usuario):
# máx. 4 CPU (CPUQuota 400%), 6 GB de RAM (MemoryMax; MemorySwapMax 1 GB), prioridad mínima de CPU y disco.
# Uso: scripts/limitado.sh comando args…   (variables LIM_MEM / LIM_CPU para cambiar los límites)
exec systemd-run --user --scope -q -p MemoryMax=${LIM_MEM:-6G} -p MemorySwapMax=1G -p CPUQuota=${LIM_CPU:-400%} \
	nice -n 19 ionice -c3 "$@"
