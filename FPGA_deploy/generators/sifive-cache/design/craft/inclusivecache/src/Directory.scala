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
import freechips.rocketchip.tilelink._
import MetaData._
import chisel3.experimental.dataview.BundleUpcastable
import freechips.rocketchip.util.DescribedSRAM

import PIC._
class DirectoryEntry(params: InclusiveCacheParameters) extends InclusiveCacheBundle(params)
{
  val dirty   = Bool() // true => TRUNK or TIP
  val state   = UInt(params.stateBits.W)
  val clients = UInt(params.clientBits.W)
  val tag     = UInt(params.tagBits.W)
}

class DirectoryWrite(params: InclusiveCacheParameters) extends InclusiveCacheBundle(params)
{
  val set  = UInt(params.setBits.W)
  val way  = UInt(params.wayBits.W)
  val data = new DirectoryEntry(params)
}

class DirectoryRead(params: InclusiveCacheParameters) extends InclusiveCacheBundle(params)
{
  val set = UInt(params.setBits.W)
  val tag = UInt(params.tagBits.W)
}

class DirectoryResult(params: InclusiveCacheParameters) extends DirectoryEntry(params)
{
  val hit = Bool()
  val way = UInt(params.wayBits.W)
}

class Directory(params: InclusiveCacheParameters,sysCfg:Sys_Config) extends Module
{
  val io = IO(new Bundle {
    val write  = Flipped(Decoupled(new DirectoryWrite(params)))
    val read   = Flipped(Valid(new DirectoryRead(params))) // sees same-cycle write
    val result = Valid(new DirectoryResult(params))
    val ready  = Bool() // reset complete; can enable access

    // IOs for switch 
    val queryFromSwitchCtl= Flipped(Decoupled(new SwitchDirQuery(params)))
    val queryResToSwitchCtl=Decoupled(new QueryRes(params))
    val dir_busy_for_switchCtl=Output(Bool())
    val picLevels=Input(UInt(log2Ceil(sysCfg.pic_avail_levels).W))
  })

  //Logic for deal with SwitchCtl >>>>>>>>>>>>>
  val sw_query_idle :: save_regout :: sw_resp ::Nil = Enum(3)
  val sw_state = RegInit(sw_query_idle)
  io.queryResToSwitchCtl.valid:=false.B
  io.queryFromSwitchCtl.ready:=false.B
  io.queryResToSwitchCtl.bits:=DontCare
  assert(io.picLevels<sysCfg.total_levels.U,"Can't take all cache!")
  val victimSumTable = (0 to params.cache.ways).map{wayID =>
      (0 to sysCfg.total_levels).map{ level=>
          ((1 << InclusiveCacheParameters.lfsrBits)*wayID)/((level+1)*sysCfg.nWay_per_level)
      }
  }
  val cache_levels=sysCfg.total_levels.U-io.picLevels
  val switchCtl_can_query= (!io.read.valid)
  // <<<<<<<<<<<<<<<<<<<<<<<<<<< Logic for deal with SwitchCtl 

  val codeBits = new DirectoryEntry(params).getWidth

  val cc_dir =  DescribedSRAM(
    name = "cc_dir",
    desc = "Directory RAM",
    size = params.cache.sets,
    data = Vec(params.cache.ways, UInt(codeBits.W))
  )

  val write = Queue(io.write, 1) // must inspect contents => max size 1
  // a flow Q creates a WaR hazard... this MIGHT not cause a problem
  // a pipe Q causes combinational loop through the scheduler

  // Wiping the Directory with 0s on reset has ultimate priority
  val wipeCount = RegInit(0.U((params.setBits + 1).W))
  val wipeOff = RegNext(false.B, true.B) // don't wipe tags during reset
  val wipeDone = wipeCount(params.setBits)
  val wipeSet = wipeCount(params.setBits - 1,0)

  io.ready := wipeDone
  when (!wipeDone && !wipeOff) { wipeCount := wipeCount + 1.U }
  assert (wipeDone || !io.read.valid)

  // Be explicit for dumb 1-port inference
  val ren = io.read.valid
  val wen = (!wipeDone && !wipeOff) || write.valid
  assert (!io.read.valid || wipeDone)

  require (codeBits <= 256)

  write.ready := !io.read.valid
  when (!ren && wen) {
    cc_dir.write(
      Mux(wipeDone, write.bits.set, wipeSet),
      VecInit.fill(params.cache.ways) { Mux(wipeDone, write.bits.data.asUInt, 0.U) },
      UIntToOH(write.bits.way, params.cache.ways).asBools.map(_ || !wipeDone))
  }

  val ren1 = RegInit(false.B)
  val ren2 = if (params.micro.dirReg) RegInit(false.B) else ren1
  ren2 := ren1
  ren1 := ren

  val bypass_valid = params.dirReg(write.valid)
  val bypass = params.dirReg(write.bits, ren1 && write.valid)
  // Change who to work with.   // val regout = params.dirReg(cc_dir.read(io.read.bits.set, ren), ren1)
  val regout = params.dirReg(cc_dir.read(Mux(io.queryFromSwitchCtl.fire,io.queryFromSwitchCtl.bits.setID,io.read.bits.set), 
                                        Mux(io.queryFromSwitchCtl.fire,true.B,ren)), ren1)
  val tag = params.dirReg(RegEnable(io.read.bits.tag, ren), ren1)
  val set = params.dirReg(RegEnable(io.read.bits.set, ren), ren1)

  // Compute the victim way in case of an evicition
  val victimLFSR = random.LFSR(width = 16, params.dirReg(ren))(InclusiveCacheParameters.lfsrBits-1, 0)
  // Choose the threshold of current level //val victimSums = Seq.tabulate(params.cache.ways) { i => ((1 << InclusiveCacheParameters.lfsrBits)*i / params.cache.ways).U }
  val victimSums = Seq.tabulate(params.cache.ways) { i => 
                              MuxCase(((1 << InclusiveCacheParameters.lfsrBits)*i / params.cache.ways).U,
                                        victimSumTable(i).zipWithIndex.map { case(sum,levelIdx) =>
                                                      ((cache_levels-1.U)===levelIdx.U) -> sum.U
                              })
  }
  val victimLTE  = Cat(victimSums.map { _ <= victimLFSR }.reverse)
  val victimSimp = Cat(0.U(1.W), victimLTE(params.cache.ways-1, 1), 1.U(1.W))
  val victimWayOH = victimSimp(params.cache.ways-1,0) & ~(victimSimp >> 1)
  val victimWay = OHToUInt(victimWayOH)
  assert (!ren2 || victimLTE(0) === 1.U)
  assert (!ren2 || ((victimSimp >> 1) & ~victimSimp) === 0.U) // monotone
  assert (!ren2 || PopCount(victimWayOH) === 1.U)

  // Assert Cache choose PIC ways as victim 
  assert (victimWay<cache_levels*(sysCfg.nWay_per_level.U),"Cache choose PIC ways as victim. This is catastrophe.")

  val setQuash = bypass_valid && bypass.set === set
  val tagMatch = bypass.data.tag === tag
  val wayMatch = bypass.way === victimWay

  val ways = regout.map(d => d.asTypeOf(new DirectoryEntry(params)))
  val hits = Cat(ways.zipWithIndex.map { case (w, i) =>
    w.tag === tag && w.state =/= INVALID && (!setQuash || i.U =/= bypass.way)
  }.reverse)
  val hit = hits.orR

  io.result.valid := ren2
  io.result.bits.viewAsSupertype(chiselTypeOf(bypass.data)) := Mux(hit, Mux1H(hits, ways), Mux(setQuash && (tagMatch || wayMatch), bypass.data, Mux1H(victimWayOH, ways)))
  io.result.bits.hit := hit || (setQuash && tagMatch && bypass.data.state =/= INVALID)
  io.result.bits.way := Mux(hit, OHToUInt(hits), Mux(setQuash && tagMatch, bypass.way, victimWay))

  // debug for verbose
  when(io.result.valid)
  { printf("victim %d\n",victimWay) }

  params.ccover(ren2 && setQuash && tagMatch, "DIRECTORY_HIT_BYPASS", "Bypassing write to a directory hit")
  params.ccover(ren2 && setQuash && !tagMatch && wayMatch, "DIRECTORY_EVICT_BYPASS", "Bypassing a write to a directory eviction")

  def json: String = s"""{"clients":${params.clientBits},"mem":"${cc_dir.pathName}","clean":"${wipeDone.pathName}"}"""

  // Added logic of find ways to flush in the request range >>>>>>>>>>>>>>>
  // val regout_for_switch_query=RegInit(0.U(codeBits .W))   // Stores values read from the directory so the directory can be freed to accept flush requests, preventing deadlock. TODO only keeps state information.
  val regout_for_switch_query=RegInit(VecInit(Seq.fill(params.cache.ways)(0.U(codeBits.W))))
  val way_info_for_switch_query=regout_for_switch_query.map(d => d.asTypeOf(new DirectoryEntry(params)))
  // dontTouch(regout_for_switch_query)
  dontTouch(regout)
  val wayPtr=RegInit(0.U((params.wayBits).W))
  val wayEnd=RegInit(0.U((params.wayBits).W))
  io.dir_busy_for_switchCtl:= (sw_state=/=sw_query_idle)
  switch(sw_state)
  {
    is(sw_query_idle)
    {
      io.queryFromSwitchCtl.ready:=true.B
      when(io.queryFromSwitchCtl.fire) // Query request from SwitchCtl
      {
        io.ready:=false.B
        wayPtr:=io.queryFromSwitchCtl.bits.beginWayID
        wayEnd:=io.queryFromSwitchCtl.bits.endWayID
        sw_state:=save_regout
      }
    }
    is(save_regout)
    {
        regout_for_switch_query:=regout
        sw_state:=sw_resp
    }
    is(sw_resp) // At this point, the results have been read out and are in the ways. The directory provides the state of the 16 ways for the given set at once.
    {
        val cur_way_state=MuxCase(0.U,(0 until params.cache.ways).map { i =>
              (i.U===wayPtr) -> way_info_for_switch_query(i).state
        })
        val cur_way_tag=MuxCase(0.U,(0 until params.cache.ways).map { i =>
              (i.U===wayPtr) -> way_info_for_switch_query(i).tag
        })
        dontTouch(cur_way_state)
        io.queryResToSwitchCtl.valid:= (cur_way_state=/=INVALID)
        io.queryResToSwitchCtl.bits.tagID:=cur_way_tag

        // Current searched way
        when(cur_way_state===INVALID)
        {
          wayPtr:=wayPtr+1.U
          sw_state:=Mux(wayPtr===wayEnd,sw_query_idle,sw_resp)
        }
        .otherwise
        {
          when(io.queryResToSwitchCtl.fire)
          {
            wayPtr:=wayPtr+1.U
            sw_state:=Mux(wayPtr===wayEnd,sw_query_idle,sw_resp)
          }
        }
    }
  }
  //<<<<<<<<<<<<<<<<< Added logic of finding ways to flush in the request range
}