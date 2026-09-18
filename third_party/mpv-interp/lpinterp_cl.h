// OpenCL 运行时按名字 dlopen,不在链接期依赖 libOpenCL.so。
//
// 硬链的话,没有 OpenCL 的机器(或厂商没把 libOpenCL.so 公开给应用的机器)上
// **整颗 libmpv 加载失败**,mpv 内核直接起不来 —— 补帧是可选功能,不能把播放拖下水。
// 只声明用到的那二十来个函数和常量,值抄自 Khronos CL/cl.h(OpenCL 1.2)。
#pragma once

#include <stddef.h>
#include <stdint.h>

typedef int32_t cl_int;
typedef uint32_t cl_uint;
typedef uint64_t cl_ulong;
typedef cl_uint cl_bool;
typedef cl_ulong cl_bitfield;
typedef cl_bitfield cl_device_type;
typedef cl_uint cl_platform_info;
typedef cl_uint cl_device_info;
typedef cl_bitfield cl_mem_flags;
typedef cl_bitfield cl_command_queue_properties;
typedef cl_uint cl_program_build_info;
typedef intptr_t cl_context_properties;
typedef struct _cl_platform_id *cl_platform_id;
typedef struct _cl_device_id *cl_device_id;
typedef struct _cl_context *cl_context;
typedef struct _cl_command_queue *cl_command_queue;
typedef struct _cl_mem *cl_mem;
typedef struct _cl_program *cl_program;
typedef struct _cl_kernel *cl_kernel;
typedef struct _cl_event *cl_event;

#define CL_SUCCESS 0
#define CL_TRUE 1
#define CL_FALSE 0
#define CL_DEVICE_TYPE_GPU (1 << 2)
#define CL_DEVICE_NAME 0x102B
#define CL_DEVICE_GLOBAL_MEM_SIZE 0x101F
#define CL_PLATFORM_NAME 0x0902
#define CL_MEM_READ_WRITE (1 << 0)
#define CL_MEM_WRITE_ONLY (1 << 1)
#define CL_MEM_READ_ONLY (1 << 2)
#define CL_PROGRAM_BUILD_LOG 0x1183

#if defined(_WIN32)
#define LPI_CL_API __stdcall
#else
#define LPI_CL_API
#endif

struct lpi_cl {
    cl_int (LPI_CL_API *GetPlatformIDs)(cl_uint, cl_platform_id *, cl_uint *);
    cl_int (LPI_CL_API *GetDeviceIDs)(cl_platform_id, cl_device_type, cl_uint, cl_device_id *, cl_uint *);
    cl_int (LPI_CL_API *GetDeviceInfo)(cl_device_id, cl_device_info, size_t, void *, size_t *);
    cl_context (LPI_CL_API *CreateContext)(const cl_context_properties *, cl_uint, const cl_device_id *,
                                           void *, void *, cl_int *);
    cl_command_queue (LPI_CL_API *CreateCommandQueue)(cl_context, cl_device_id, cl_command_queue_properties, cl_int *);
    cl_mem (LPI_CL_API *CreateBuffer)(cl_context, cl_mem_flags, size_t, void *, cl_int *);
    cl_program (LPI_CL_API *CreateProgramWithSource)(cl_context, cl_uint, const char **, const size_t *, cl_int *);
    cl_int (LPI_CL_API *BuildProgram)(cl_program, cl_uint, const cl_device_id *, const char *, void *, void *);
    cl_int (LPI_CL_API *GetProgramBuildInfo)(cl_program, cl_device_id, cl_program_build_info, size_t, void *, size_t *);
    cl_kernel (LPI_CL_API *CreateKernel)(cl_program, const char *, cl_int *);
    cl_int (LPI_CL_API *SetKernelArg)(cl_kernel, cl_uint, size_t, const void *);
    cl_int (LPI_CL_API *EnqueueNDRangeKernel)(cl_command_queue, cl_kernel, cl_uint, const size_t *, const size_t *,
                                               const size_t *, cl_uint, const cl_event *, cl_event *);
    cl_int (LPI_CL_API *EnqueueWriteBuffer)(cl_command_queue, cl_mem, cl_bool, size_t, size_t, const void *,
                                             cl_uint, const cl_event *, cl_event *);
    cl_int (LPI_CL_API *EnqueueReadBuffer)(cl_command_queue, cl_mem, cl_bool, size_t, size_t, void *,
                                            cl_uint, const cl_event *, cl_event *);
    cl_int (LPI_CL_API *EnqueueFillBuffer)(cl_command_queue, cl_mem, const void *, size_t, size_t, size_t,
                                            cl_uint, const cl_event *, cl_event *);
    cl_int (LPI_CL_API *Finish)(cl_command_queue);
    cl_int (LPI_CL_API *ReleaseMemObject)(cl_mem);
    cl_int (LPI_CL_API *ReleaseKernel)(cl_kernel);
    cl_int (LPI_CL_API *ReleaseProgram)(cl_program);
    cl_int (LPI_CL_API *ReleaseCommandQueue)(cl_command_queue);
    cl_int (LPI_CL_API *ReleaseContext)(cl_context);
};
