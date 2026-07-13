#include "utils/plt_resolver.h"

/**
 * @file plt_resolver.cpp
 * @brief Implementation for symbol resolution helpers used by the inline hook
 * and trampoline code paths.
 */

#include <dlfcn.h>
#include <elf.h>
#include <sys/mman.h>

#include <algorithm>
#include <cstdint>
#include <cstring>
#include <optional>
#include <string_view>

#include "utils/proc_maps_scanner.h"

namespace echidna
{
    namespace utils
    {

        namespace
        {
            constexpr size_t kMaxVendorSuffix = 16;

            struct DynamicInfo
            {
                uintptr_t symtab{0};
                uintptr_t strtab{0};
                uintptr_t jmprel{0};
                size_t pltrelsz{0};
                int pltrel_type{0};
                uintptr_t rel{0};
                size_t relsz{0};
                uintptr_t rela{0};
                size_t relasz{0};
                uintptr_t hash{0};
                uintptr_t gnu_hash{0};
                size_t syment{0};
            };

            struct ElfImage
            {
                uintptr_t base{0};
                int elf_class{0};
                DynamicInfo info{};
            };

            uintptr_t ResolvePointer(uintptr_t base, uintptr_t ptr)
            {
                if (ptr == 0)
                {
                    return 0;
                }
                if (ptr >= base)
                {
                    return ptr;
                }
                return base + ptr;
            }

            std::string_view StripVersionSuffix(std::string_view name)
            {
                const size_t at = name.find('@');
                if (at == std::string_view::npos)
                {
                    return name;
                }
                return name.substr(0, at);
            }

            bool IsExecutableAddress(uintptr_t address)
            {
                if (address == 0)
                {
                    return false;
                }
                ProcMapsScanner scanner;
                for (const auto &region : scanner.regions())
                {
                    if (region.permissions.find('x') == std::string::npos)
                    {
                        continue;
                    }
                    if (address >= region.start && address < region.end)
                    {
                        return true;
                    }
                }
                return false;
            }

            std::optional<ElfImage> LoadElfImage(const std::string &library)
            {
                ProcMapsScanner scanner;
                uintptr_t base = 0;
                bool found = false;
                for (const auto &region : scanner.regions())
                {
                    if (region.path.empty())
                    {
                        continue;
                    }
                    if (region.path.find(library) == std::string::npos)
                    {
                        continue;
                    }
                    if (!found || region.start < base)
                    {
                        base = region.start;
                        found = true;
                    }
                }
                if (!found || base == 0)
                {
                    return std::nullopt;
                }
                const auto *ident = reinterpret_cast<const unsigned char *>(base);
                if (!ident || std::memcmp(ident, ELFMAG, SELFMAG) != 0)
                {
                    return std::nullopt;
                }
                int elf_class = ident[EI_CLASS];
                if (elf_class != ELFCLASS32 && elf_class != ELFCLASS64)
                {
                    return std::nullopt;
                }
                ElfImage image;
                image.base = base;
                image.elf_class = elf_class;
                return image;
            }

            template <typename EhdrT, typename PhdrT, typename DynT>
            bool ParseDynamicInfo(uintptr_t base, DynamicInfo &info)
            {
                const auto *ehdr = reinterpret_cast<const EhdrT *>(base);
                if (!ehdr || std::memcmp(ehdr->e_ident, ELFMAG, SELFMAG) != 0)
                {
                    return false;
                }
                const auto *phdrs =
                    reinterpret_cast<const PhdrT *>(base + static_cast<uintptr_t>(ehdr->e_phoff));
                if (!phdrs)
                {
                    return false;
                }
                const PhdrT *dynamic_phdr = nullptr;
                for (uint16_t i = 0; i < ehdr->e_phnum; ++i)
                {
                    if (phdrs[i].p_type == PT_DYNAMIC)
                    {
                        dynamic_phdr = &phdrs[i];
                        break;
                    }
                }
                if (!dynamic_phdr)
                {
                    return false;
                }
                const auto *dyn =
                    reinterpret_cast<const DynT *>(base + static_cast<uintptr_t>(dynamic_phdr->p_vaddr));
                if (!dyn)
                {
                    return false;
                }
                for (const DynT *entry = dyn; entry->d_tag != DT_NULL; ++entry)
                {
                    switch (entry->d_tag)
                    {
                    case DT_SYMTAB:
                        info.symtab = ResolvePointer(base, static_cast<uintptr_t>(entry->d_un.d_ptr));
                        break;
                    case DT_STRTAB:
                        info.strtab = ResolvePointer(base, static_cast<uintptr_t>(entry->d_un.d_ptr));
                        break;
                    case DT_SYMENT:
                        info.syment = static_cast<size_t>(entry->d_un.d_val);
                        break;
                    case DT_JMPREL:
                        info.jmprel = ResolvePointer(base, static_cast<uintptr_t>(entry->d_un.d_ptr));
                        break;
                    case DT_PLTRELSZ:
                        info.pltrelsz = static_cast<size_t>(entry->d_un.d_val);
                        break;
                    case DT_PLTREL:
                        info.pltrel_type = static_cast<int>(entry->d_un.d_val);
                        break;
                    case DT_RELA:
                        info.rela = ResolvePointer(base, static_cast<uintptr_t>(entry->d_un.d_ptr));
                        break;
                    case DT_RELASZ:
                        info.relasz = static_cast<size_t>(entry->d_un.d_val);
                        break;
                    case DT_REL:
                        info.rel = ResolvePointer(base, static_cast<uintptr_t>(entry->d_un.d_ptr));
                        break;
                    case DT_RELSZ:
                        info.relsz = static_cast<size_t>(entry->d_un.d_val);
                        break;
                    case DT_HASH:
                        info.hash = ResolvePointer(base, static_cast<uintptr_t>(entry->d_un.d_ptr));
                        break;
                    case DT_GNU_HASH:
                        info.gnu_hash = ResolvePointer(base, static_cast<uintptr_t>(entry->d_un.d_ptr));
                        break;
                    default:
                        break;
                    }
                }
                return info.symtab != 0 && info.strtab != 0;
            }

            size_t CountSymbolsFromSysvHash(uintptr_t hash_addr)
            {
                if (!hash_addr)
                {
                    return 0;
                }
                const auto *table = reinterpret_cast<const uint32_t *>(hash_addr);
                const uint32_t nchain = table[1];
                return static_cast<size_t>(nchain);
            }

            template <typename AddrT>
            size_t CountSymbolsFromGnuHash(uintptr_t gnu_hash_addr)
            {
                if (!gnu_hash_addr)
                {
                    return 0;
                }
                const auto *header = reinterpret_cast<const uint32_t *>(gnu_hash_addr);
                const uint32_t nbuckets = header[0];
                const uint32_t symoffset = header[1];
                const uint32_t bloom_size = header[2];
                const uint8_t *ptr = reinterpret_cast<const uint8_t *>(gnu_hash_addr);
                ptr += sizeof(uint32_t) * 4;
                ptr += static_cast<size_t>(bloom_size) * sizeof(AddrT);
                const auto *buckets = reinterpret_cast<const uint32_t *>(ptr);
                const auto *chains = buckets + nbuckets;
                size_t max_index = 0;
                bool found = false;
                for (uint32_t i = 0; i < nbuckets; ++i)
                {
                    uint32_t bucket = buckets[i];
                    if (bucket == 0)
                    {
                        continue;
                    }
                    if (bucket < symoffset)
                    {
                        continue;
                    }
                    size_t idx = bucket;
                    while (true)
                    {
                        uint32_t hash = chains[idx - symoffset];
                        max_index = std::max(max_index, idx);
                        found = true;
                        if (hash & 1u)
                        {
                            break;
                        }
                        ++idx;
                    }
                }
                if (!found)
                {
                    return static_cast<size_t>(symoffset);
                }
                return max_index + 1;
            }

            template <typename SymT>
            bool MatchesSymbol(std::string_view target, const SymT &sym, const char *strtab)
            {
                if (!strtab || sym.st_name == 0)
                {
                    return false;
                }
                const std::string_view name = strtab + sym.st_name;
                if (name.empty())
                {
                    return false;
                }
                if (StripVersionSuffix(name) == target)
                {
                    return true;
                }
                return false;
            }

            template <typename SymT>
            uint8_t SymbolType(const SymT &sym)
            {
                return ELF64_ST_TYPE(sym.st_info);
            }

            template <>
            uint8_t SymbolType<Elf32_Sym>(const Elf32_Sym &sym)
            {
                return ELF32_ST_TYPE(sym.st_info);
            }

            template <typename RelT>
            uint32_t RelocationSymbolIndex(const RelT &rel)
            {
                return static_cast<uint32_t>(ELF64_R_SYM(rel.r_info));
            }

            template <>
            uint32_t RelocationSymbolIndex<Elf32_Rel>(const Elf32_Rel &rel)
            {
                return static_cast<uint32_t>(ELF32_R_SYM(rel.r_info));
            }

            template <>
            uint32_t RelocationSymbolIndex<Elf32_Rela>(const Elf32_Rela &rel)
            {
                return static_cast<uint32_t>(ELF32_R_SYM(rel.r_info));
            }

            template <>
            uint32_t RelocationSymbolIndex<Elf64_Rela>(const Elf64_Rela &rel)
            {
                return static_cast<uint32_t>(ELF64_R_SYM(rel.r_info));
            }

            template <typename SymT, typename RelT>
            void *ResolveFromRelocations(const DynamicInfo &info,
                                         uintptr_t base,
                                         std::string_view target,
                                         const RelT *rels,
                                         size_t count)
            {
                if (!rels || count == 0 || info.symtab == 0 || info.strtab == 0)
                {
                    return nullptr;
                }
                const auto *symtab = reinterpret_cast<const SymT *>(info.symtab);
                const char *strtab = reinterpret_cast<const char *>(info.strtab);
                for (size_t i = 0; i < count; ++i)
                {
                    const RelT &rel = rels[i];
                    const uint32_t sym_index = RelocationSymbolIndex(rel);
                    const SymT &sym = symtab[sym_index];
                    if (!MatchesSymbol(target, sym, strtab))
                    {
                        continue;
                    }
                    const uintptr_t got_addr = ResolvePointer(base, rel.r_offset);
                    if (got_addr)
                    {
                        const uintptr_t resolved = *reinterpret_cast<const uintptr_t *>(got_addr);
                        if (IsExecutableAddress(resolved))
                        {
                            return reinterpret_cast<void *>(resolved);
                        }
                    }
                    const uintptr_t sym_addr = ResolvePointer(base, sym.st_value);
                    if (IsExecutableAddress(sym_addr))
                    {
                        return reinterpret_cast<void *>(sym_addr);
                    }
                }
                return nullptr;
            }

            template <typename SymT, typename AddrT>
            void *ResolveByHeuristic(const DynamicInfo &info,
                                     uintptr_t base,
                                     std::string_view symbol)
            {
                if (info.symtab == 0 || info.strtab == 0)
                {
                    return nullptr;
                }
                size_t sym_count = 0;
                if (info.hash)
                {
                    sym_count = CountSymbolsFromSysvHash(info.hash);
                }
                else if (info.gnu_hash)
                {
                    sym_count = CountSymbolsFromGnuHash<AddrT>(info.gnu_hash);
                }
                if (sym_count == 0)
                {
                    return nullptr;
                }
                const auto *symtab = reinterpret_cast<const SymT *>(info.symtab);
                const char *strtab = reinterpret_cast<const char *>(info.strtab);
                const std::string_view target = StripVersionSuffix(symbol);
                const SymT *match = nullptr;
                size_t matches = 0;
                for (size_t i = 0; i < sym_count; ++i)
                {
                    const SymT &sym = symtab[i];
                    if (sym.st_name == 0 || sym.st_value == 0)
                    {
                        continue;
                    }
                    if (SymbolType(sym) != STT_FUNC)
                    {
                        continue;
                    }
                    std::string_view name = strtab + sym.st_name;
                    name = StripVersionSuffix(name);
                    if (name == target)
                    {
                        match = &sym;
                        matches = 1;
                        break;
                    }
                    if (name.size() >= target.size() &&
                        name.compare(0, target.size(), target) == 0 &&
                        (name.size() - target.size()) <= kMaxVendorSuffix)
                    {
                        match = &sym;
                        ++matches;
                    }
                }
                if (matches == 1 && match)
                {
                    const uintptr_t sym_addr = ResolvePointer(base, match->st_value);
                    if (IsExecutableAddress(sym_addr))
                    {
                        return reinterpret_cast<void *>(sym_addr);
                    }
                }
                return nullptr;
            }
        } // namespace

        /** Lookup an exported symbol using dlopen/dlsym. */
        void *PltResolver::findSymbol(const std::string &library, const std::string &symbol) const
        {
            void *handle = dlopen(library.c_str(), RTLD_LAZY | RTLD_NOLOAD);
            if (!handle)
            {
                handle = dlopen(library.c_str(), RTLD_LAZY);
            }
            if (!handle)
            {
                return nullptr;
            }
            if (void *addr = dlsym(handle, symbol.c_str()))
            {
                return addr;
            }
            auto image = LoadElfImage(library);
            if (!image)
            {
                return nullptr;
            }
            if (image->elf_class == ELFCLASS64)
            {
                if (!ParseDynamicInfo<Elf64_Ehdr, Elf64_Phdr, Elf64_Dyn>(image->base, image->info))
                {
                    return nullptr;
                }
                const std::string_view target = StripVersionSuffix(symbol);
                if (image->info.jmprel && image->info.pltrelsz > 0)
                {
                    if (image->info.pltrel_type == DT_RELA)
                    {
                        auto *rels = reinterpret_cast<const Elf64_Rela *>(image->info.jmprel);
                        void *addr = ResolveFromRelocations<Elf64_Sym>(image->info,
                                                                       image->base,
                                                                       target,
                                                                       rels,
                                                                       image->info.pltrelsz /
                                                                           sizeof(Elf64_Rela));
                        if (addr)
                        {
                            return addr;
                        }
                    }
                    else
                    {
                        auto *rels = reinterpret_cast<const Elf64_Rel *>(image->info.jmprel);
                        void *addr = ResolveFromRelocations<Elf64_Sym>(image->info,
                                                                       image->base,
                                                                       target,
                                                                       rels,
                                                                       image->info.pltrelsz /
                                                                           sizeof(Elf64_Rel));
                        if (addr)
                        {
                            return addr;
                        }
                    }
                }
                return ResolveByHeuristic<Elf64_Sym, Elf64_Addr>(image->info,
                                                                 image->base,
                                                                 target);
            }
            if (!ParseDynamicInfo<Elf32_Ehdr, Elf32_Phdr, Elf32_Dyn>(image->base, image->info))
            {
                return nullptr;
            }
            const std::string_view target = StripVersionSuffix(symbol);
            if (image->info.jmprel && image->info.pltrelsz > 0)
            {
                if (image->info.pltrel_type == DT_RELA)
                {
                    auto *rels = reinterpret_cast<const Elf32_Rela *>(image->info.jmprel);
                    void *addr = ResolveFromRelocations<Elf32_Sym>(image->info,
                                                                   image->base,
                                                                   target,
                                                                   rels,
                                                                   image->info.pltrelsz /
                                                                       sizeof(Elf32_Rela));
                    if (addr)
                    {
                        return addr;
                    }
                }
                else
                {
                    auto *rels = reinterpret_cast<const Elf32_Rel *>(image->info.jmprel);
                    void *addr = ResolveFromRelocations<Elf32_Sym>(image->info,
                                                                   image->base,
                                                                   target,
                                                                   rels,
                                                                   image->info.pltrelsz /
                                                                       sizeof(Elf32_Rel));
                    if (addr)
                    {
                        return addr;
                    }
                }
            }
            return ResolveByHeuristic<Elf32_Sym, Elf32_Addr>(image->info,
                                                             image->base,
                                                             target);
        }

        /** Scan executable regions of a library looking for a byte signature. */
        void *PltResolver::findSymbolBySignature(const std::string &library, const std::vector<uint8_t> &signature) const
        {
            if (signature.empty())
            {
                return nullptr;
            }

            ProcMapsScanner scanner;
            auto region = scanner.findRegion([&library](const MemoryRegion &region)
                                             { return region.path.find(library) != std::string::npos && region.permissions.find("x") != std::string::npos; });
            if (!region)
            {
                return nullptr;
            }

            size_t size = region->end - region->start;
            const uint8_t *base = reinterpret_cast<const uint8_t *>(region->start);
            for (size_t i = 0; i + signature.size() <= size; ++i)
            {
                if (std::memcmp(base + i, signature.data(), signature.size()) == 0)
                {
                    return const_cast<uint8_t *>(base + i);
                }
            }
            return nullptr;
        }

    } // namespace utils
} // namespace echidna
