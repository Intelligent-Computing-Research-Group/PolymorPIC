import re
import math

def test_main_logic_code(file,testID,testInfo,wordline_num,valid,printMatrix):

    level_alloc_src = resolve_PIC_range(testInfo["srcArrayID"]>>2)
    level_alloc_dst = resolve_PIC_range(testInfo["destArrayID"]>>2)
    level_alloc = max(level_alloc_src,level_alloc_dst)

    print(level_alloc)

    alloc(file,level_alloc)

    file.write(f"\tprintf(\"Test {testID} begin.\\n\");\n")

    file.write(f'''
    // ------------- Load part ----------------
    ''')


    file.write(f'''
    for(int srcID=0;srcID<{testID}_srcNum;srcID++)
    {{
        uint64_t srcAddr=(unsigned long){testID}_srcArray_content[srcID];
        uint32_t picArrayID=(uint32_t){testID}_srcArrayID_list[srcID];
        LOAD(
            srcAddr,
            picArrayID<<{int(math.log(wordline_num,2))},
            {testID}_len_64b,
            8,
            8,
            srcID
            );
    }}
    ''')

    file.write(f'''
    for(int srcID=0;srcID<{testID}_srcNum;srcID++)
    {{
        waitEnqCmdFree(srcID);
    }}
    ''')
    file.write(f'''
    printf("Load finish!");\n
    ''')

    file.write(f'''
    // -------------ACC----------------
    ''')

    file.write(f'''
    uint32_t {testID}_start,{testID}_end;
    ''')

    cmdID=0
    file.write(f'''
    ACC(
        (uint32_t){testID}_srcArrayID_list[0]<<{int(math.log(wordline_num,2))},
        (uint32_t){testID}_destArrayID<<{int(math.log(wordline_num,2))},
        {cmdID},
        {testID}_srcNum,
        {testID}_len_64b,
        {testID}_bitWidth
        );
    ''')

    file.write(f'''
    waitEnqCmdFree({cmdID});
    ''')


    file.write(f'''
    // -------------Store----------------
    ''')
    file.write(f'''
    uint64_t {testID}_resMatAddr=(unsigned long){testID}_resMatOut;
    uint32_t {testID}_picArrayID=(uint32_t){testID}_destArrayID;
    STORE(
        {testID}_picArrayID<<{int(math.log(wordline_num,2))},
        {testID}_resMatAddr,
        {testID}_len_64b,
        8,
        8,
        {cmdID}
        );
    ''')

    file.write(f'''
    waitEnqCmdFree({cmdID});
    ''')

    free(file,level_alloc)

    file.write(f'''
    // -------------Check----------------
    ''')

    bitwidth=32 if testInfo["bitWidth"]=="_32b_" else 16
    num_per_row=int(64/bitwidth)

    file.write(f'''
    validation_{bitwidth}(
        {testID}_resMatTrue,
        {testID}_resMatOut,
        {testID}_len_64b,
        {num_per_row}
    );
    ''')

    # file.write(f'''
    # printBlockHexArray_{bitwidth}({testID}_resMatOut,
    #                                 0,0,
    #                                 {testID}_len_64b,{num_per_row});
    # ''')


def resolve_PIC_range(beginMatID):
    with open("../ISA.h", 'r') as file:
        content = file.read()

    match_mat_per_level = re.search(r'#define\s+MAT_PER_LEVEL\s+(\d+)', content)
    match_levels = re.search(r'#define\s+TOTAL_LEVEL\s+(\d+)', content)

    if match_mat_per_level:
        mat_per_level=int(match_mat_per_level.group(1))
        levels=int(match_levels.group(1))
        assert(int(beginMatID/mat_per_level)>0)
        return levels-int(beginMatID/mat_per_level)
    else:
        raise ValueError("MAT_SIZE_KB definition not found in the file")
    

def alloc(file,levels):
    file.write(f'''
    #ifdef PIC_MODE
        conduct_alloc({levels});
    #endif
    ''')


def free(file,levels):
    file.write(f'''
    #ifdef PIC_MODE
        conduct_free({levels});
    #endif
    ''')
