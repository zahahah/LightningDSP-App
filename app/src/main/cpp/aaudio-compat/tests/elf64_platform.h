#pragma once
#include <cstdint>
using Elf64_Addr = uint64_t;
using Elf64_Off = uint64_t;
using Elf64_Xword = uint64_t;
using Elf64_Sxword = int64_t;
using Elf64_Word = uint32_t;
using Elf64_Half = uint16_t;
struct Elf64_Ehdr { unsigned char e_ident[16]; Elf64_Half e_type,e_machine; Elf64_Word e_version; Elf64_Addr e_entry; Elf64_Off e_phoff,e_shoff; Elf64_Word e_flags; Elf64_Half e_ehsize,e_phentsize,e_phnum,e_shentsize,e_shnum,e_shstrndx; };
struct Elf64_Phdr { Elf64_Word p_type,p_flags; Elf64_Off p_offset; Elf64_Addr p_vaddr,p_paddr; Elf64_Xword p_filesz,p_memsz,p_align; };
struct Elf64_Dyn { Elf64_Sxword d_tag; union { Elf64_Xword d_val; Elf64_Addr d_ptr; } d_un; };
struct Elf64_Sym { Elf64_Word st_name; unsigned char st_info,st_other; Elf64_Half st_shndx; Elf64_Addr st_value; Elf64_Xword st_size; };
struct Elf64_Rela { Elf64_Addr r_offset; Elf64_Xword r_info; Elf64_Sxword r_addend; };
constexpr unsigned PT_LOAD=1,PT_DYNAMIC=2,PT_GNU_RELRO=0x6474e552;
constexpr unsigned PF_X=1,PF_W=2,PF_R=4;
constexpr int ET_DYN=3,EM_AARCH64=183,EV_CURRENT=1,ELFCLASS64=2,ELFDATA2LSB=1;
constexpr int EI_CLASS=4,EI_DATA=5,EI_VERSION=6;
constexpr int DT_NULL=0,DT_PLTRELSZ=2,DT_HASH=4,DT_STRTAB=5,DT_SYMTAB=6,DT_RELA=7,DT_RELAENT=9,DT_STRSZ=10,DT_SYMENT=11,DT_PLTREL=20,DT_JMPREL=23,DT_GNU_HASH=0x6ffffef5;
constexpr unsigned R_AARCH64_JUMP_SLOT=1026,SHN_UNDEF=0,STT_FUNC=2,STB_GLOBAL=1,STV_DEFAULT=0;
#define ELF64_R_SYM(i) ((i)>>32)
#define ELF64_R_TYPE(i) ((uint32_t)(i))
#define ELF64_ST_TYPE(i) ((i)&15)
#define ELF64_ST_BIND(i) ((i)>>4)
#define ELF64_ST_VISIBILITY(i) ((i)&3)
#define ELF64_ST_INFO(b,t) (((b)<<4)|((t)&15))
#define ELF64_R_INFO(s,t) ((static_cast<uint64_t>(s)<<32)|(t))
