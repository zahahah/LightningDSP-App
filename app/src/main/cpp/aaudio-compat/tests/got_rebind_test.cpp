// Copyright (C) 2026 LightningDSP contributors
// SPDX-License-Identifier: Apache-2.0
#define LDSP_AAUDIO_COMPAT_HOST_TEST
#include "../got_rebind.h"
#include <cassert>
#include <cstdio>
#include <unistd.h>
using namespace ldsp::got;
struct Fixture {
    size_t page = static_cast<size_t>(sysconf(_SC_PAGESIZE));
    unsigned char* bytes = static_cast<unsigned char*>(mmap(nullptr, page * 4, PROT_READ|PROT_WRITE, MAP_PRIVATE|MAP_ANON, -1, 0));
    std::array<Target,5> targets{{{"create",0x1000,0x2000},{"direction",0x1100,0x2100},{"performance",0x1200,0x2200},{"open",0x1300,0x2300},{"delete",0x1400,0x2400}}};
    Elf64_Phdr* ph = reinterpret_cast<Elf64_Phdr*>(bytes+sizeof(Elf64_Ehdr));
    Elf64_Dyn* dyn = reinterpret_cast<Elf64_Dyn*>(bytes+0x300);
    Elf64_Sym* sym = reinterpret_cast<Elf64_Sym*>(bytes+0x500);
    Elf64_Rela* rel = reinterpret_cast<Elf64_Rela*>(bytes+0x800);
    uintptr_t* slots = reinterpret_cast<uintptr_t*>(bytes+page*2);
    Fixture() {
        assert(bytes!=MAP_FAILED);
        auto* eh=reinterpret_cast<Elf64_Ehdr*>(bytes);
        std::memcpy(eh->e_ident,"\177ELF",4);eh->e_ident[EI_CLASS]=ELFCLASS64;eh->e_ident[EI_DATA]=ELFDATA2LSB;eh->e_ident[EI_VERSION]=EV_CURRENT;
        eh->e_type=ET_DYN;eh->e_machine=EM_AARCH64;eh->e_version=EV_CURRENT;eh->e_ehsize=sizeof(*eh);eh->e_phoff=sizeof(*eh);eh->e_phentsize=sizeof(*ph);eh->e_phnum=3;
        ph[0].p_type=PT_LOAD;ph[0].p_flags=PF_R|PF_W;ph[0].p_memsz=page*4;ph[0].p_filesz=page*4;
        ph[1].p_type=PT_DYNAMIC;ph[1].p_vaddr=0x300;ph[1].p_memsz=11*sizeof(Elf64_Dyn);
        ph[2].p_type=PT_GNU_RELRO;ph[2].p_vaddr=page*2;ph[2].p_memsz=page;
        const int64_t tags[]={DT_SYMTAB,DT_STRTAB,DT_STRSZ,DT_SYMENT,DT_JMPREL,DT_PLTRELSZ,DT_PLTREL,DT_RELAENT,DT_HASH,DT_NULL};
        const uint64_t values[]={0x500,0x700,0x100,sizeof(Elf64_Sym),0x800,5*sizeof(Elf64_Rela),DT_RELA,sizeof(Elf64_Rela),0xa00,0};
        for(size_t i=0;i<10;++i){dyn[i].d_tag=tags[i];dyn[i].d_un.d_val=values[i];}
        auto* hash=reinterpret_cast<uint32_t*>(bytes+0xa00);hash[0]=1;hash[1]=6;
        size_t offset=1;
        for(size_t i=0;i<5;++i){sym[i+1].st_name=offset;sym[i+1].st_info=ELF64_ST_INFO(STB_GLOBAL,STT_FUNC);std::strcpy(reinterpret_cast<char*>(bytes+0x700+offset),targets[i].name);offset+=std::strlen(targets[i].name)+1;rel[i].r_offset=page*2+i*sizeof(uintptr_t);rel[i].r_info=ELF64_R_INFO(i+1,R_AARCH64_JUMP_SLOT);slots[i]=targets[i].original;}
    }
    ~Fixture(){munmap(bytes,page*4);}
    bool plan(Plan& result){return prepare({reinterpret_cast<uintptr_t>(bytes),ph,3},targets,page,result);}
};
struct Protection { int calls=0; int fail=0; uintptr_t* corrupt=nullptr; };
bool protect(void* opaque,uintptr_t page,size_t size,int mode){auto& p=*static_cast<Protection*>(opaque);++p.calls;if(p.calls==p.fail)return false;const bool ok=mprotect(reinterpret_cast<void*>(page),size,mode)==0;if(ok&&p.calls==1&&p.corrupt)*p.corrupt=0xdead;return ok;}
int main(){
    // OpenSL's independent one-symbol plan uses the same fail-closed ELF gate.
    for(size_t targetPage : {size_t{4096}, size_t{16384}}) {
        Fixture f; BasicPlan<1> p;
        const std::array<Target,1> one{{f.targets[0]}};
        assert(prepare({reinterpret_cast<uintptr_t>(f.bytes),f.ph,3},one,targetPage,p));
        assert(p.slots[0].address == f.slots && p.pageSize == targetPage);
    }
    {Fixture f;BasicPlan<1> p; const std::array<Target,1> one{{f.targets[0]}};
        assert(prepare({reinterpret_cast<uintptr_t>(f.bytes),f.ph,3},one,f.page,p));
        assert(mprotect(f.slots,f.page,PROT_READ)==0); Protection state;
        assert(apply(p,PROT_READ,protect,&state)==Result::Applied);
        assert(f.slots[0]==f.targets[0].replacement && f.slots[1]==f.targets[1].original);
    }

    {Fixture f;Plan p;assert(f.plan(p));assert(mprotect(f.slots,f.page,PROT_READ)==0);Protection state;assert(apply(p,PROT_READ,protect,&state)==Result::Applied);assert(state.calls==2);for(size_t i=0;i<5;++i)assert(f.slots[i]==f.targets[i].replacement);assert(f.plan(p)&&p.alreadyApplied);assert(apply(p,PROT_READ,protect,&state)==Result::AlreadyApplied);}
    {Fixture f;Plan p;assert(f.plan(p));assert(apply(p,PROT_READ|PROT_EXEC,protect,nullptr)==Result::Rejected);}
    {Fixture f;Plan p;assert(f.plan(p));assert(mprotect(f.slots,f.page,PROT_READ)==0);Protection state;state.fail=1;assert(apply(p,PROT_READ,protect,&state)==Result::Rejected);}
    {Fixture f;Plan p;assert(f.plan(p));assert(mprotect(f.slots,f.page,PROT_READ)==0);Protection state;state.fail=2;assert(apply(p,PROT_READ,protect,&state)==Result::RolledBack);for(size_t i=0;i<5;++i)assert(f.slots[i]==f.targets[i].original);assert(state.calls==4);}
    {Fixture f;Plan p;assert(f.plan(p));assert(mprotect(f.slots,f.page,PROT_READ)==0);Protection state;state.corrupt=&f.slots[3];assert(apply(p,PROT_READ,protect,&state)==Result::RolledBack);for(size_t i=0;i<3;++i)assert(f.slots[i]==f.targets[i].original);}
    // GNU-only hash: bounded buckets/chains and corrupt unterminated chain.
    for(bool corrupt : {false,true}) {
        Fixture f; Plan p; f.dyn[8].d_tag=DT_GNU_HASH;
        auto* h=reinterpret_cast<uint32_t*>(f.bytes+0xa00);
        h[0]=1;h[1]=1;h[2]=1;h[3]=5;h[4]=0;h[5]=0;h[6]=1;
        for(int i=0;i<5;++i)h[7+i]=(i==4&&!corrupt)?1:0;
        assert(f.plan(p)==!corrupt);
    }
    {Fixture f;Plan p; f.ph[2].p_type=0; assert(f.plan(p));Protection state;
        assert(apply(p,PROT_READ|PROT_WRITE,protect,&state)==Result::Applied);assert(state.calls==0);}
    for(int mutation=0;mutation<14;++mutation){Fixture f;Plan p;switch(mutation){
        case 0:f.dyn[0].d_un.d_val=UINT64_MAX;break;
        case 1:f.dyn[2].d_un.d_val=UINT64_MAX;break;
        case 2:f.sym[1].st_name=0x100;break;
        case 3:f.rel[1].r_offset=f.rel[0].r_offset;break;
        case 4:f.rel[1].r_offset=f.page*3;break;
        case 5:f.targets[1].original=f.targets[0].original;break;
        case 6:f.slots[0]=0xdead;break;
        case 7:f.ph[0].p_flags|=PF_X;break;
        case 8:f.dyn[5].d_un.d_val-=sizeof(Elf64_Rela);break;
        case 9:f.rel[0].r_info=ELF64_R_INFO(999,R_AARCH64_JUMP_SLOT);break;
        case 10:f.rel[0].r_addend=1;break;
        case 11:f.dyn[6].d_un.d_val=0;break;
        case 12:f.sym[1].st_info=ELF64_ST_INFO(STB_GLOBAL,0);break;
        case 13:reinterpret_cast<Elf64_Ehdr*>(f.bytes)->e_machine=0;break;
        }assert(!f.plan(p));}
    std::puts("ELF GOT bounds, corruption, RELRO, external hooks and rollback tests passed");
}
