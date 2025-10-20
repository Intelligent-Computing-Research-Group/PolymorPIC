package chipyard

import org.chipsalliance.cde.config.{Config}
import freechips.rocketchip.diplomacy.{AsynchronousCrossing}
import freechips.rocketchip.subsystem.{InCluster}



class PICRocket1024 extends Config(
  new PIC.WithRoccInterface ++
  new freechips.rocketchip.subsystem.WithoutTLMonitors ++
  new freechips.rocketchip.subsystem.WithNBanks(1) ++
  new freechips.rocketchip.subsystem.WithInclusiveCache(nWays=16, capacityKB=1024) ++
  new freechips.rocketchip.subsystem.WithNBigCores(1) ++         // single rocket-core
  new chipyard.config.AbstractConfig)
