#ifndef ROCC_H
#define ROCC_H

// funct3 flags
#define ROCC_XD     0x4
#define ROCC_XS1    0x2
#define ROCC_XS2    0x1

/*
* xd=1, xs=1, xs2=1 
* rs1 and rs2 will be sent to accel through command channel. 
* the rd value from response channel will be saved to rd
* the command has WB to rd
*/
#define ROCC_INSTRUCTION_DSS(X, rd, rs1, rs2, funct) \
    ROCC_INSTRUCTION_R_R_R(X, rd, rs1, rs2, funct)

/*
* xd=1, xs=1, xs2=0 
* rs1 will be sent to accel through command channel. 
* rs2 will not be sent to accel as the flag is 0, even if you specify it in the command
* the rd value from response channel will be saved to rd
* the command has WB to rd.
*/
#define ROCC_INSTRUCTION_DS(X, rd, rs1, funct) \
    ROCC_INSTRUCTION_R_R_I(X, rd, rs1, 0, funct)

/*
* xd=1, xs=0, xs2=0 
* rs1 and rs2 will not be sent to accel through command channel as the flag is 0, even if you specify it in the command
* the rd value from response channel will be saved to rd
* the command has WB to rd.
*/
#define ROCC_INSTRUCTION_D(X, rd, funct) \
    ROCC_INSTRUCTION_R_I_I(X, rd, 0, 0, funct)

/*
* xd=0, xs=1, xs2=1 
* rs1 and rs2 will be sent to accel through command channel. 
* the rd value from response channel will be ignored
* the command has no WB, even if the accel return rd.
*/
#define ROCC_INSTRUCTION_SS(X, rs1, rs2, funct) \
    ROCC_INSTRUCTION_I_R_R(X, 0, rs1, rs2, funct)

/*
* xd=0, xs=1, xs2=0 
* rs1 will be sent to accel through command channel. rs2 will not
* the rd value from response channel will be ignored (actually if accel reconigze the flag, it will not return it)
* the command has no WB, even if the accel return rd.
*/
#define ROCC_INSTRUCTION_S(X, rs1, funct) \
    ROCC_INSTRUCTION_I_R_I(X, 0, rs1, 0, funct)

/*
* xd=0, xs=0, xs2=0 
* rs1 and rs2 will not be sent to accel through command channel. 
* the rd value from response channel will be ignored (actually if accel reconigze the flag, it will not return it)
* the command has no WB, even if the accel return rd.
* the cmd act only as inst transfer
*/
#define ROCC_INSTRUCTION(X, funct) \
    ROCC_INSTRUCTION_I_I_I(X, 0, 0, 0, funct)



#define ROCC_INSTRUCTION_R_R_R(X, rd, rs1, rs2, funct)                  \
    do {                                                                \
        __asm__ __volatile__ (                                          \
            ".insn r CUSTOM_" #X ", %3, %4, %0, %1, %2\n\t"             \
            : "=r" (rd)                                                 \
            : "r" (rs1), "r" (rs2),                                     \
              "i" (ROCC_XD | ROCC_XS1 | ROCC_XS2), "i" (funct));        \
    } while (0)


#define ROCC_INSTRUCTION_R_R_I(X, rd, rs1, rs2, funct)                  \
    do {                                                                \
        __asm__ __volatile__ (                                          \
            ".insn r CUSTOM_" #X ", %3, %4, %0, %1, x%2\n\t"            \
            : "=r" (rd)                                                 \
            : "r" (rs1), "K" (rs2),                                     \
              "i" (ROCC_XD | ROCC_XS1), "i" (funct));                   \
    } while (0)


#define ROCC_INSTRUCTION_R_I_I(X, rd, rs1, rs2, funct)                  \
    do {                                                                \
        __asm__ __volatile__ (                                          \
            ".insn r CUSTOM_" #X ", %3, %4, %0, x%1, x%2\n\t"           \
            : "=r" (rd)                                                 \
            : "K" (rs1), "K" (rs2),                                     \
              "i" (ROCC_XD), "i" (funct));                              \
    } while (0)


#define ROCC_INSTRUCTION_I_R_R(X, rd, rs1, rs2, funct)                  \
    do {                                                                \
        __asm__ __volatile__ (                                          \
            ".insn r CUSTOM_" #X ", %3, %4, x%0, %1, %2\n\t"            \
            :                                                           \
            : "K" (rd), "r" (rs1), "r" (rs2),                           \
              "i" (ROCC_XS1 | ROCC_XS2), "i" (funct));                  \
    } while (0)


#define ROCC_INSTRUCTION_I_R_I(X, rd, rs1, rs2, funct)                  \
    do {                                                                \
        __asm__ __volatile__ (                                          \
            ".insn r CUSTOM_" #X ", %3, %4, x%0, %1, x%2\n\t"           \
            :                                                           \
            : "K" (rd), "r" (rs1), "K" (rs2),                           \
              "i" (ROCC_XS1), "i" (funct));                             \
    } while (0)

#define ROCC_INSTRUCTION_I_I_I(X, rd, rs1, rs2, funct)                  \
    do {                                                                \
        __asm__ __volatile__ (                                          \
            ".insn r CUSTOM_" #X ", %3, %4, x%0, x%1, x%2\n\t"          \
            :                                                           \
            : "K" (rd), "K" (rs1), "K" (rs2),                           \
              "i" (0), "i" (funct));                                    \
    } while (0)

#define rdcycle() read_csr(cycle)
#define read_csr(reg) ({ unsigned long __tmp; \
  asm volatile ("csrr %0, " #reg : "=r"(__tmp)); \
  __tmp; })



#endif