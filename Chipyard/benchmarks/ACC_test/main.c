#include <stdio.h>
#include <string.h>
#ifdef LINUX
	#include <sys/mman.h>
	#include <stdlib.h>
#endif
#include "data.h"
#include "ISA.h"
#include "func.h"
#include "config.h"

int main()
{

    #ifdef LINUX
        if (mlockall(MCL_CURRENT | MCL_FUTURE) != 0) 
        {
            perror("mlockall failed");
            exit(1);
        }
    #endif
    
    #ifdef PIC_MODE
        conduct_alloc(15);
    #endif
    	printf("Test a1 begin.\n");

    // ------------- Load part ----------------
    
    for(int srcID=0;srcID<a1_srcNum;srcID++)
    {
        uint64_t srcAddr=(unsigned long)a1_srcArray_content[srcID];
        uint32_t picArrayID=(uint32_t)a1_srcArrayID_list[srcID];
        LOAD(
            srcAddr,
            picArrayID<<9,
            a1_len_64b,
            8,
            8,
            srcID
            );
    }
    
    for(int srcID=0;srcID<a1_srcNum;srcID++)
    {
        waitEnqCmdFree(srcID);
    }
    
    printf("Load finish!");

    
    // -------------ACC----------------
    
    uint32_t a1_start,a1_end;
    
    ACC(
        (uint32_t)a1_srcArrayID_list[0]<<9,
        (uint32_t)a1_destArrayID<<9,
        0,
        a1_srcNum,
        a1_len_64b,
        a1_bitWidth
        );
    
    waitEnqCmdFree(0);
    
    // -------------Store----------------
    
    uint64_t a1_resMatAddr=(unsigned long)a1_resMatOut;
    uint32_t a1_picArrayID=(uint32_t)a1_destArrayID;
    STORE(
        a1_picArrayID<<9,
        a1_resMatAddr,
        a1_len_64b,
        8,
        8,
        0
        );
    
    waitEnqCmdFree(0);
    
    #ifdef PIC_MODE
        conduct_free(15);
    #endif
    
    // -------------Check----------------
    
    validation_32(
        a1_resMatTrue,
        a1_resMatOut,
        a1_len_64b,
        2
    );
    
    #ifdef PIC_MODE
        conduct_alloc(12);
    #endif
    	printf("Test a2 begin.\n");

    // ------------- Load part ----------------
    
    for(int srcID=0;srcID<a2_srcNum;srcID++)
    {
        uint64_t srcAddr=(unsigned long)a2_srcArray_content[srcID];
        uint32_t picArrayID=(uint32_t)a2_srcArrayID_list[srcID];
        LOAD(
            srcAddr,
            picArrayID<<9,
            a2_len_64b,
            8,
            8,
            srcID
            );
    }
    
    for(int srcID=0;srcID<a2_srcNum;srcID++)
    {
        waitEnqCmdFree(srcID);
    }
    
    printf("Load finish!");

    
    // -------------ACC----------------
    
    uint32_t a2_start,a2_end;
    
    ACC(
        (uint32_t)a2_srcArrayID_list[0]<<9,
        (uint32_t)a2_destArrayID<<9,
        0,
        a2_srcNum,
        a2_len_64b,
        a2_bitWidth
        );
    
    waitEnqCmdFree(0);
    
    // -------------Store----------------
    
    uint64_t a2_resMatAddr=(unsigned long)a2_resMatOut;
    uint32_t a2_picArrayID=(uint32_t)a2_destArrayID;
    STORE(
        a2_picArrayID<<9,
        a2_resMatAddr,
        a2_len_64b,
        8,
        8,
        0
        );
    
    waitEnqCmdFree(0);
    
    #ifdef PIC_MODE
        conduct_free(12);
    #endif
    
    // -------------Check----------------
    
    validation_32(
        a2_resMatTrue,
        a2_resMatOut,
        a2_len_64b,
        2
    );
    
}
