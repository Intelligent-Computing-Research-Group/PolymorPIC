package PIC

import chisel3._
import chisel3.util._

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import scala.collection.mutable.Buffer
import scala.collection.mutable.ListBuffer

import java.nio.file.{Files, Paths, StandardCopyOption}
import scala.util.Try

class TraceClient(
    val ClientName:String
)
{
    private val fields: ListBuffer[(String, UInt)] = ListBuffer()

    def addCustomField(field_name: String,field: UInt): Unit=
    {
        fields += (field_name -> field)
    }

    def genYAML(yaml_str:StringBuilder,id_map:Map[String, Int],len_limit:Int): StringBuilder=
    {
        yaml_str++="\n"
        yaml_str++=s"${ClientName}:\n"
        yaml_str++=s" ClientID: ${id_map(ClientName)}\n"



        yaml_str++=s" HasSecondTrace: ${if(fields.length==0) 0 else 1}\n"

        var total_len=0
        var filed_start_ptr=0
        fields.foreach { case (field_name, field_UInt) =>
            yaml_str++=s" ${field_name}:\n"
            yaml_str++=s"  Begin: ${filed_start_ptr}\n"
            yaml_str++=s"  Len: ${field_UInt.getWidth}\n"
            filed_start_ptr=filed_start_ptr+field_UInt.getWidth
            total_len+=field_UInt.getWidth
        }

        assert(total_len<=len_limit,s"${ClientName}Too long${total_len}, adjust the custom_filed_length in TraceCfg!")

        yaml_str++="\n"

        yaml_str
    }
}

object RecStateType
{
    val START_RUN=0.U
    val END_RUN=1.U
}


//  TIME,STATE,ID
//  IF STATE=START, there will be the second record, recording the details
//  The details is custom for each module.
//  IF STATE=START, there will be no second record.
case class TraceCfg(
    val pic_full_addr_len:Int,
    val helper:TraceInfoHelper=TraceInfoHelper()
)
{
    // The module to trace and there ID definition
    val ID_Map: Map[String, Int] = Map(
        "P2S_R" -> 0,
        "P2S_L" -> 1,
        "LOAD_P" -> 2,
        "STORE_P" -> 3,
        "ACC" -> 4,
        "EXE" -> 5
    )
    val client_num=ID_Map.size

    // State
    val state_count = 2

    // Time range define
    val trace_time_stmp_field_len=36

    // The sram size for keeping the trace width is 64b for default
    val trace_depth=1024

    // custom filed length
    val custom_filed_length=34

    // Path for saving the resolve file
    // The first file resolve where is ID STATE TIME
    // The second file tells how to resolve the custo details. It was configed by modeules, when make the wiring.
    val trace_config_path="/root/chipyard/generators/PolymorPIC/src/main/scala/TraceRec/trace_cfg.yaml"
    val custom_file_resolve_yaml_path="/root/chipyard/generators/PolymorPIC/src/main/scala/TraceRec/custom_res.yaml"

    assert((log2Ceil(client_num)+log2Ceil(state_count)+trace_time_stmp_field_len+pic_full_addr_len)<=64,"Execed len limitation.")

    def genConfigYAML():Unit={
        helper.genResYaml(path=trace_config_path,
                            Client_ID_len=log2Ceil(client_num),ID_begin=0,
                            STATE_len=log2Ceil(state_count),STATE_begin=log2Ceil(client_num),
                            TIME_len=trace_time_stmp_field_len,TIME_begin=log2Ceil(client_num)+log2Ceil(state_count),
                            PicAddr_len=pic_full_addr_len,PicAddr_begin=log2Ceil(client_num)+log2Ceil(state_count)+trace_time_stmp_field_len)
        helper.genCustomFieldResYaml(path=custom_file_resolve_yaml_path,id_map=ID_Map,custom_field_len_limit=custom_filed_length)
    }

}


// The actual clients' are kept its list
case class TraceInfoHelper()
{
    val clients_list: ListBuffer[TraceClient] = ListBuffer()
    def addTraceClient(client:TraceClient):Unit=
    {
        clients_list += client
    }

    def genResYaml(path:String,
                    Client_ID_len:Int,ID_begin:Int,
                    STATE_len:Int,STATE_begin:Int,
                    TIME_len:Int,TIME_begin:Int,
                    PicAddr_len:Int,PicAddr_begin:Int):Unit=
    {
        val _str_ = new StringBuilder()
        val file_path=Paths.get(path)
        val parentDir = file_path.getParent
        if (!Files.exists(parentDir)) {
            Files.createDirectories(parentDir)
        }

        // ------ID------
        _str_ ++="ID:\n"
        _str_ ++=s" Begin: ${ID_begin}\n"
        _str_ ++=s" Len: ${Client_ID_len}\n\n"

        // ------State------
        _str_ ++="STATE:\n"
        _str_ ++=s" Begin: ${STATE_begin}\n"
        _str_ ++=s" Len: ${STATE_len}\n\n"

        // -----TIME-------
        _str_ ++="TIME:\n"
        _str_ ++=s" Begin: ${TIME_begin}\n"
        _str_ ++=s" Len: ${TIME_len}\n\n"

        // -----PicAddr-------
        _str_ ++="PicAddr:\n"
        _str_ ++=s" Begin: ${PicAddr_begin}\n"
        _str_ ++=s" Len: ${PicAddr_len}\n\n"

        Files.write(file_path, _str_.toString().getBytes(StandardCharsets.UTF_8))
    }

    def genCustomFieldResYaml(path:String,custom_field_len_limit:Int,id_map : Map[String, Int]):Unit=
    {
        val file_path=Paths.get(path)
        val yaml_str = new StringBuilder()
        val parentDir = file_path.getParent
        if (!Files.exists(parentDir)) {
            Files.createDirectories(parentDir)
        }

        for (client <- clients_list) {
            client.genYAML(yaml_str=yaml_str,
                            id_map=id_map,
                            len_limit=custom_field_len_limit)
        }

        Files.write(file_path, yaml_str.toString().getBytes(StandardCharsets.UTF_8))
    }


}

