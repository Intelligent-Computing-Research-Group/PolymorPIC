/*
 * Copyright 2019 SiFive, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You should have received a copy of LICENSE.Apache2 along with
 * this software. If not, you may obtain a copy at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package sifive.blocks.inclusivecache

import chisel3._
import chisel3.util._

import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._

import freechips.rocketchip.subsystem.{SubsystemBankedCoherenceKey}
import freechips.rocketchip.regmapper._
import freechips.rocketchip.tilelink._

// import pic
import PIC.PolymorPIC_main_in_cache
import PIC.Sys_Config
import PIC.PolymorPIC_Kernal_Config
import PIC.NewISA


class InclusiveCache(
  val cache: CacheParameters,
  val micro: InclusiveCacheMicroParameters,
  control: Option[InclusiveCacheControlParameters] = None
  )(implicit p: Parameters)
    extends LazyModule
{
  val access = TransferSizes(1, cache.blockBytes)
  val xfer = TransferSizes(cache.blockBytes, cache.blockBytes)
  val atom = TransferSizes(1, cache.beatBytes)

  var resourcesOpt: Option[ResourceBindings] = None

  val device: SimpleDevice = new SimpleDevice("cache-controller", Seq("sifive,inclusivecache0", "cache")) {
    def ofInt(x: Int) = Seq(ResourceInt(BigInt(x)))

    override def describe(resources: ResourceBindings): Description = {
      resourcesOpt = Some(resources)

      val Description(name, mapping) = super.describe(resources)
      // Find the outer caches
      val outer = node.edges.out
        .flatMap(_.manager.managers)
        .filter(_.supportsAcquireB)
        .flatMap(_.resources.headOption)
        .map(_.owner.label)
        .distinct
      val nextlevel: Option[(String, Seq[ResourceValue])] =
        if (outer.isEmpty) {
          None
        } else {
          Some("next-level-cache" -> outer.map(l => ResourceReference(l)).toList)
        }

      val extra = Map(
        "cache-level"            -> ofInt(2),
        "cache-unified"          -> Nil,
        "cache-size"             -> ofInt(cache.sizeBytes * node.edges.in.size),
        "cache-sets"             -> ofInt(cache.sets * node.edges.in.size),
        "cache-block-size"       -> ofInt(cache.blockBytes),
        "sifive,mshr-count"      -> ofInt(InclusiveCacheParameters.all_mshrs(cache, micro)))
      Description(name, mapping ++ extra ++ nextlevel)
    }
  }

  val node: TLAdapterNode = TLAdapterNode(
    clientFn  = { _ => TLClientPortParameters(Seq(TLClientParameters(
      name          = s"L${cache.level} InclusiveCache",
      sourceId      = IdRange(0, InclusiveCacheParameters.out_mshrs(cache, micro)),
      supportsProbe = xfer)))
    },
    managerFn = { m => TLManagerPortParameters(
      managers = m.managers.map { m => m.copy(
        regionType         = if (m.regionType >= RegionType.UNCACHED) RegionType.CACHED else m.regionType,
        resources          = Resource(device, "caches") +: m.resources,
        supportsAcquireB   = xfer,
        supportsAcquireT   = if (m.supportsAcquireT) xfer else TransferSizes.none,
        supportsArithmetic = if (m.supportsAcquireT) atom else TransferSizes.none,
        supportsLogical    = if (m.supportsAcquireT) atom else TransferSizes.none,
        supportsGet        = access,
        supportsPutFull    = if (m.supportsAcquireT) access else TransferSizes.none,
        supportsPutPartial = if (m.supportsAcquireT) access else TransferSizes.none,
        supportsHint       = access,
        alwaysGrantsT      = false,
        fifoId             = None)
      },
      beatBytes  = cache.beatBytes,
      endSinkId  = InclusiveCacheParameters.all_mshrs(cache, micro),
      minLatency = 2)
    })

  val ctrls = control.map { c =>
    val nCtrls = if (c.bankedControl) p(SubsystemBankedCoherenceKey).nBanks else 1
    Seq.tabulate(nCtrls) { i => LazyModule(new InclusiveCacheControl(this,
      c.copy(address = c.address + i * InclusiveCacheParameters.L2ControlSize))) }
  }.getOrElse(Nil)

  // pic define >>>>>>>>>>>>>>>>>>
  val polymorpic_sysCfg=Sys_Config(
                                    cache_top_param=cache,
                                    cache_micro_param=micro,
                                    en_custom=true,
                                    custom_cacheSizeBytes=1048576,   // 测试用的1024KB cache
                                    custom_waysPerSet=16             // 测试用的1024KB cache
                                    )
  // val polymorpic_sysCfg=Sys_Config(
  //                                   cache_top_param=cache,
  //                                   cache_micro_param=micro,
  //                                   en_custom=true,
  //                                   custom_cacheSizeBytes=2097152,   // 测试用的1024KB cache
  //                                   custom_waysPerSet=16             // 测试用的1024KB cache
  //                                   )
  // val polymorpic_ISA=ISA(polymorpic_sysCfg)
  val polymor_pic = LazyModule(new PolymorPIC_main_in_cache(polymorpic_sysCfg)(p))
  val pic_dmaReaderNode=polymor_pic.dmaReaderNode
  val pic_dmaWriterNode=polymor_pic.dmaWriterNode
  val pic_tlb_req_master_node=polymor_pic.tlb_req_master_node
  val pic_reveice_cmd_node=polymor_pic.reveice_cmd_node
  // polymorpic_ISA.genHeaderFile()

  val test_ISA=NewISA(polymorpic_sysCfg)
  test_ISA.genHeaderFile()

  // <<<<<<<<<< pic define

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    // If you have a control port, you must have at least one cache port
    require (ctrls.isEmpty || !node.edges.in.isEmpty)

    // Extract the client IdRanges; must be the same on all ports!
    val clientIds = node.edges.in.headOption.map(_.client.clients.map(_.sourceId).sortBy(_.start))
    node.edges.in.foreach { e => require(e.client.clients.map(_.sourceId).sortBy(_.start) == clientIds.get) }

    // Use the natural ordering of clients (just like in Directory)
    node.edges.in.headOption.foreach { n =>
      println(s"L${cache.level} InclusiveCache Client Map:")
      n.client.clients.zipWithIndex.foreach { case (c,i) =>
        println(s"\t${i} <= ${c.name}")
      }
      println("")
    }

    // Create the L2 Banks
    val mods = (node.in zip node.out) map { case ((in, edgeIn), (out, edgeOut)) =>
      edgeOut.manager.managers.foreach { m =>
        require (m.supportsAcquireB.contains(xfer),
          s"All managers behind the L2 must support acquireB($xfer) " +
          s"but ${m.name} only supports (${m.supportsAcquireB})!")
        if (m.supportsAcquireT) require (m.supportsAcquireT.contains(xfer),
          s"Any probing managers behind the L2 must support acquireT($xfer) " +
          s"but ${m.name} only supports (${m.supportsAcquireT})!")
    }

    val params = InclusiveCacheParameters(cache, micro, !ctrls.isEmpty, edgeIn, edgeOut)
    val scheduler = Module(new InclusiveCacheBankScheduler(params,polymorpic_sysCfg)).suggestName("inclusive_cache_bank_sched")

      scheduler.io.in <> in
      out <> scheduler.io.out
      scheduler.io.ways := DontCare
      scheduler.io.divs := DontCare

      // Tie down default values in case there is no controller
      scheduler.io.req.valid := false.B
      scheduler.io.req.bits.address := 0.U
      scheduler.io.resp.ready := true.B


      // Fix-up the missing addresses. We do this here so that the Scheduler can be
      // deduplicated by Firrtl to make hierarchical place-and-route easier.
      out.a.bits.address := params.restoreAddress(scheduler.io.out.a.bits.address)
      in .b.bits.address := params.restoreAddress(scheduler.io.in .b.bits.address)
      out.c.bits.address := params.restoreAddress(scheduler.io.out.c.bits.address)

      scheduler
    }

    ctrls.foreach { ctrl =>
      ctrl.module.io.flush_req.ready := false.B
      ctrl.module.io.flush_resp := false.B
      ctrl.module.io.flush_match := false.B
    }

    mods.zip(node.edges.in).zipWithIndex.foreach { case ((sched, edgeIn), i) =>
      val ctrl = if (ctrls.size > 1) Some(ctrls(i)) else ctrls.headOption
      ctrl.foreach { ctrl => {
        val contained = edgeIn.manager.managers.flatMap(_.address)
          .map(_.contains(ctrl.module.io.flush_req.bits)).reduce(_||_)
        when (contained) { ctrl.module.io.flush_match := true.B }

        sched.io.req.valid := contained && ctrl.module.io.flush_req.valid
        sched.io.req.bits.address := ctrl.module.io.flush_req.bits
        when (contained && sched.io.req.ready) { ctrl.module.io.flush_req.ready := true.B }

        when (sched.io.resp.valid) { ctrl.module.io.flush_resp := true.B }
        sched.io.resp.ready := true.B
      }}
    }

      val sche=mods(0)
      val pic_near_cache=polymor_pic.module
      val picIO=pic_near_cache.io
      // Connection of SwitchCtl with schedular and polymorpic >>>>>>>>>>>>>>>>>>
      // switch
      pic_near_cache.io.switch_req<>sche.io.switch_req
      pic_near_cache.io.switch_resp<>sche.io.switch_resp
      // <<<<<<<<<<<<<<<<<<<<<<<<<<<<<< Connection of SwitchCtl with schedular and polymorpic

      // Connection of PIC's data, setup, L, state >>>>>>>>>>>>>>>>>>
      val scheIO=sche.io
      scheIO.picMem_enable  :=  picIO.picMem_enable
      scheIO.picMem_write   :=  picIO.picMem_write
      scheIO.picMem_addr    :=  picIO.picMem_addr
      scheIO.picMem_dataIn  :=  picIO.picMem_dataOut
      picIO.picMem_dataIn   :=  scheIO.picMem_dataOut

      scheIO.set_up_addr    :=  picIO.set_up_addr
      scheIO.set_up_io      :=  picIO.set_up_io

      scheIO.request_vec    <>  picIO.request_vec
      scheIO.response_vec   <>  picIO.response_vec
      scheIO.load_vec_en    :=  picIO.load_vec_en
      scheIO.load_vec_addr  :=  picIO.load_vec_addr
      scheIO.vec_data       :=  picIO.vec_data

      scheIO.query_mat_req  <>  picIO.query_mat_req
      picIO.mat_busy        :=  scheIO.mat_busy

      picIO.mem_ready       :=  scheIO.mem_ready
      // <<<<<<<<<<<<<<<<<<<<<<<<<<<<<< Connection of PIC's data, setup, L, state 

    def json = s"""{"banks":[${mods.map(_.json).mkString(",")}]}"""
  }
}
