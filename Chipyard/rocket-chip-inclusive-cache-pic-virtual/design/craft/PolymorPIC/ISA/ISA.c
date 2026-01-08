#include "ISA.h"

void setSrcInfo(
	uint64_t srcAddress
)
{
	uint64_t rs1=0;
	rs1=srcAddress;

	ROCC_INSTRUCTION_S(ROCC_OPCODE,rs1,0);
}

void setDstInfo(
	uint64_t dstAddress
)
{
	uint64_t rs1=0;
	rs1=dstAddress;

	ROCC_INSTRUCTION_S(ROCC_OPCODE,rs1,1);
}

void setSizeInfo(
	uint16_t offset,
	uint16_t bytePerRow,
	uint16_t row
)
{
	uint64_t rs1=0;
	rs1=offset;
	rs1=(rs1<<11)+bytePerRow;
	rs1=(rs1<<11)+row;

	ROCC_INSTRUCTION_S(ROCC_OPCODE,rs1,2);
}

uint64_t setParamInfo(
	uint64_t others,
	uint8_t cmdID,
	uint8_t moduleID
)
{
	uint64_t rs1=0;
	rs1=others;
	rs1=(rs1<<8)+cmdID;
	rs1=(rs1<<4)+moduleID;

	unsigned long rd = -1;
	ROCC_INSTRUCTION_DS(ROCC_OPCODE,rd,rs1,3);
	return rd;
}


/*----------------Frontend ISA implementation---------------*/


void LOAD(
	uint64_t memSrcAddr,	// The dram addr of src.
	uint32_t onChipDstAddr,	// The on-chip addr of dst.
	uint16_t matRow,	// Number of row in matrix.
	uint16_t mat_Col,	// The number bytes each row in matrix.
	uint16_t rowOffset,	// The number bytes between row[i][0] and row[i+1][0].
	uint8_t cmdID	// cmdID
){
	uint64_t srcAddress_cmb=memSrcAddr;
	setSrcInfo(srcAddress_cmb);
	uint64_t dstAddress_cmb=onChipDstAddr;
	setDstInfo(dstAddress_cmb);
	uint16_t row_cmb=matRow;
	uint16_t bytePerRow_cmb=mat_Col;
	uint16_t offset_cmb=rowOffset;
	setSizeInfo(offset_cmb,bytePerRow_cmb,row_cmb);
	uint8_t moduleID_cmb=0;
	uint8_t cmdID_cmb=cmdID;
	setParamInfo(0,cmdID_cmb,moduleID_cmb);
}


void P2SL(
	uint64_t memSrcAddr,	// The dram addr of src.
	uint32_t onChipDstAddr,	// he on-chip addr of dst.
	uint8_t matL_Row,	// Number of row in left matrix.
	uint16_t rowOffset,	// The number bytes between row[i][0] and row[i+1][0].
	uint8_t cmdID,	// cmdID
	uint8_t bitWidth	// The valid bit of elements in L. Needs to -1!
){
	uint64_t srcAddress_cmb=memSrcAddr;
	setSrcInfo(srcAddress_cmb);
	uint64_t dstAddress_cmb=onChipDstAddr;
	setDstInfo(dstAddress_cmb);
	uint16_t row_cmb=matL_Row;
	uint16_t offset_cmb=rowOffset;
	setSizeInfo(offset_cmb,0,row_cmb);
	uint8_t moduleID_cmb=1;
	uint8_t cmdID_cmb=cmdID;
	uint64_t others_cmb=bitWidth;
	setParamInfo(others_cmb,cmdID_cmb,moduleID_cmb);
}


void P2SR(
	uint64_t memSrcAddr,	// The dram addr of src.
	uint32_t onChipDstAddr,	// he on-chip addr of dst.
	uint16_t matR_Row,	// Number of row in right matrix.
	uint16_t matR_Col,	// Number of col in right matrix.
	uint16_t rowOffset,	// The number bytes between row[i][0] and row[i+1][0].
	uint8_t cmdID,	// cmdID
	uint8_t nBufPerMat,	// Use how many buf in each mat.
	uint8_t bitWidth,	// The valid bit of elements in R. Needs to -1!
	bool if_T	// If transpos.
){
	uint64_t srcAddress_cmb=memSrcAddr;
	setSrcInfo(srcAddress_cmb);
	uint64_t dstAddress_cmb=onChipDstAddr;
	setDstInfo(dstAddress_cmb);
	uint16_t row_cmb=matR_Row;
	uint16_t bytePerRow_cmb=matR_Col;
	uint16_t offset_cmb=rowOffset;
	setSizeInfo(offset_cmb,bytePerRow_cmb,row_cmb);
	uint8_t moduleID_cmb=2;
	uint8_t cmdID_cmb=cmdID;
	uint64_t others_cmb=if_T;
	others_cmb=(others_cmb<<3)+bitWidth;
	others_cmb=(others_cmb<<2)+nBufPerMat;
	setParamInfo(others_cmb,cmdID_cmb,moduleID_cmb);
}


void IM2COL(
	uint64_t memSrcAddr,	// The dram addr of src.
	uint64_t memDstAddr,	// The dram addr of dst.
	uint16_t featrueSize,	// The height/width of feature map before padding.
	uint8_t kernalSize,	// The kernal size.
	uint8_t strideSize,	// The stride size.
	uint8_t nPad,	// The pad size.
	bool toCol	// 是否要写回dram,否则只pad并留在bank上
){
	uint64_t srcAddress_cmb=memSrcAddr;
	setSrcInfo(srcAddress_cmb);
	uint64_t dstAddress_cmb=memDstAddr;
	setDstInfo(dstAddress_cmb);
	uint8_t moduleID_cmb=4;
	uint64_t others_cmb=toCol;
	others_cmb=(others_cmb<<4)+nPad;
	others_cmb=(others_cmb<<4)+strideSize;
	others_cmb=(others_cmb<<4)+kernalSize;
	others_cmb=(others_cmb<<9)+featrueSize;
	setParamInfo(others_cmb,0,moduleID_cmb);
}


void ACC(
	uint32_t onChipSrcAddr,	// The on-chip addr of dst.
	uint32_t onChipDstAddr,	// The on-chip addr of dst.
	uint8_t cmdID,	// cmdID
	uint8_t srcNum,	// Add how many partial sum. The offset between src is 4 array.
	uint16_t accRowNum,	// The number of row(64b cyrrently) to acc.
	uint8_t bitWidth	// 1bit. The bitWdth of elem. 1->32b,0->16b
){
	uint64_t srcAddress_cmb=onChipSrcAddr;
	setSrcInfo(srcAddress_cmb);
	uint64_t dstAddress_cmb=onChipDstAddr;
	setDstInfo(dstAddress_cmb);
	uint8_t moduleID_cmb=5;
	uint8_t cmdID_cmb=cmdID;
	uint64_t others_cmb=bitWidth;
	others_cmb=(others_cmb<<11)+accRowNum;
	others_cmb=(others_cmb<<4)+srcNum;
	setParamInfo(others_cmb,cmdID_cmb,moduleID_cmb);
}


void EXE(
	uint32_t baseLAddr,	// The base address of L matrix.
	uint8_t targetMatID,	// The target matID.
	uint8_t cmdID,	// cmdID
	uint16_t R_Valid_nRols,	// How many R rols.(In the array, it is the num of valid row of R).
	uint8_t nBufPerMat,	// How many arrays used as buffer to keep P's partial sum.
	uint8_t nCalPerMat,	// How many arrays used to conduct MAC.(The left array except used as buffers will be MAC mode.However, not all of them must work.
	uint8_t Base_R_Bit,	// The first R bit slice's bit position. The left R bit slices are +1 one by one based on it.
	uint8_t L_Precision,	// What is L bitWidth. It should -1. e.g., if it is 8b, write 7 here.
	uint8_t L_Block_Row,	// Number of row in left matrix block.
	bool SignL,	// If L is signed..
	bool SignR_bitLast,	// If R is signed and the highest bit is in this exe.
	bool accWidth,	// The accWidth. 32b->true;16b->false
	bool needL	// This exe need to a new loaded L to begin, meanging the L it needs is not in array.
){
	uint64_t srcAddress_cmb=baseLAddr;
	setSrcInfo(srcAddress_cmb);
	uint64_t dstAddress_cmb=targetMatID;
	setDstInfo(dstAddress_cmb);
	uint8_t moduleID_cmb=6;
	uint8_t cmdID_cmb=cmdID;
	uint64_t others_cmb=needL;
	others_cmb=(others_cmb<<1)+accWidth;
	others_cmb=(others_cmb<<1)+SignR_bitLast;
	others_cmb=(others_cmb<<1)+SignL;
	others_cmb=(others_cmb<<8)+L_Block_Row;
	others_cmb=(others_cmb<<3)+L_Precision;
	others_cmb=(others_cmb<<3)+Base_R_Bit;
	others_cmb=(others_cmb<<2)+nCalPerMat;
	others_cmb=(others_cmb<<2)+nBufPerMat;
	others_cmb=(others_cmb<<10)+R_Valid_nRols;
	setParamInfo(others_cmb,cmdID_cmb,moduleID_cmb);
}


void STORE(
	uint64_t onChipSrcAddr,	// The on-chip addr of src.
	uint64_t memDstAddr,	// The dram addr of dst.
	uint16_t matRow,	// Number of row in matrix.Need to be reduced by one!
	uint16_t matCol,	// The number bytes each row in matrix.
	uint16_t rowOffset,	// The number bytes between row[i][0] and row[i+1][0].
	uint8_t cmdID	// cmdID
){
	uint64_t srcAddress_cmb=onChipSrcAddr;
	setSrcInfo(srcAddress_cmb);
	uint64_t dstAddress_cmb=memDstAddr;
	setDstInfo(dstAddress_cmb);
	uint16_t row_cmb=matRow;
	uint16_t bytePerRow_cmb=matCol;
	uint16_t offset_cmb=rowOffset;
	setSizeInfo(offset_cmb,bytePerRow_cmb,row_cmb);
	uint8_t moduleID_cmb=7;
	uint8_t cmdID_cmb=cmdID;
	setParamInfo(0,cmdID_cmb,moduleID_cmb);
}


void SWITCH(
	bool opType,	// Bool(true) is ALLOC, Bool(false) is FREE.
	uint8_t nLevels	// How many clusters of mats to Alloc. Total 15 is available.
){
	uint8_t moduleID_cmb=8;
	uint64_t others_cmb=nLevels;
	others_cmb=(others_cmb<<1)+opType;
	setParamInfo(others_cmb,0,moduleID_cmb);
}

// end_matID;begin_matID;whether the switch op is success(valid op);whether the command finish;
bool ifSwitchSuccess(uint64_t query_res)
{	return ((query_res>>2)&1)==1;	}

uint32_t getBeginPicMatID(uint64_t query_res)
{	return ((query_res>>3)&63);	}

uint32_t getEndPicMatID(uint64_t query_res)
{	return ((query_res>>9)&63);	}

#define EN_PRINT true
void showSwitchInfo(uint64_t query_res)
{
    bool switchRes=ifSwitchSuccess(query_res);
    uint8_t beginMatID=getBeginPicMatID(query_res);
    uint8_t endMatID=getEndPicMatID(query_res);
    uint8_t availableMat=(endMatID-beginMatID)==0?0:endMatID-beginMatID+1;
    uint16_t availablePIC_KB=availableMat*MAT_SIZE_KB;
    uint16_t availableCache_KB=CACHE_SIZE_KB-availablePIC_KB;

    if(switchRes)
    {   printf("Switch successfully!\n");  }
    else
    {   printf("Switch FAILED, operation was not executed\n");}

    printf("################ Runtime State ###############\n");
    #ifdef LINUX
        float percentage_PIC = (float)availablePIC_KB/(float)CACHE_SIZE_KB;
        printf("Totally %f%% Cache Memory is used as Computation\n",percentage_PIC*100);
    #endif
    // printFloatBinary(percentage_PIC);
    printf("Available Cache Volumn (KB) = %d\n",availableCache_KB);
    printf("Available PIC Volumn (KB) = %d\n",availablePIC_KB);
    printf("Available Mats = %d\n",availableMat);
    printf("Available Mats Range = %d~%d\n",beginMatID,endMatID);
    printf("##############################################\n");
}

void conduct_alloc(uint8_t nLevels)
{
    SWITCH(ALLOC,nLevels);
    waitImmedFree();
    uint64_t query_res=QUERY(255,true);
    #ifdef EN_PRINT
        showSwitchInfo(query_res);
    #endif
} 

void conduct_free(uint8_t nLevels)
{
    SWITCH(FREE,nLevels);
    waitImmedFree();
    uint64_t query_res=QUERY(255,true);
    #ifdef EN_PRINT
        showSwitchInfo(query_res);
    #endif
}

uint64_t QUERY(
	uint8_t cmdID,	// cmdID
	bool is_immed	// Whether the queried command is immed type command.
){
	uint8_t moduleID_cmb=9;
	uint8_t cmdID_cmb=cmdID;
	uint64_t others_cmb=is_immed;
	return setParamInfo(others_cmb,cmdID_cmb,moduleID_cmb);
}


void waitImmedFree()
{
    while(((QUERY(255,true)>>1)&1)==1)
    {}
}

void waitEnqCmdFree(uint8_t cmdID) // The valid cmdID range is [0,255) !!! 255 is for immed cmd.
{
    // These command needs two query; The first for query table; The second get the result.
    QUERY(cmdID,false);
    while(((QUERY(cmdID,false))&1)==0)
    {QUERY(cmdID,false);}
}
