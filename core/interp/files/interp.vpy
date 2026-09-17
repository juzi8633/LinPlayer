# LinPlayer 补帧脚本。由 mpv 的 vf=vapoursynth 加载,参数从 user-data 传进来:
#   algo=drba|rife  x=倍数  gpu=DXGI 适配器序号  h=补帧前降到的高度
#
# 不直接调 k7sfunc 的 DRBA_DML / RIFE_DML:它们把 gpu 钉死在 0/1/2 三个值,
# 而机器上有虚拟显示适配器(远程桌面软件装的那种)时,独显的序号会排到 3 以后。
import vapoursynth as vs
from vapoursynth import core
from k7sfunc._internal import vs_t_dft
from k7sfunc.mod_fmt import FMT_CTRL
from k7sfunc.mod_memc import DRBA_HUB, RIFE_ORT_HUB
from k7sfunc._external import vsmlrt

core.num_threads = vs_t_dft
opt = dict(kv.split("=", 1) for kv in str(user_data).split(";") if "=" in kv)
algo = opt.get("algo", "drba")
multi = int(opt.get("x", "2"))
gpu = int(opt.get("gpu", "0"))
h_max = int(opt.get("h", "1080"))

clip = video_in
fps = float(container_fps)
# 高帧率片源原样放行。核心层已经不让选,这里是第二道:抛异常的话 mpv 会整条滤镜停掉并报错。
if 0 < fps <= 32:
    # 输出锁 8 位 420。10 位片源原样补出来是 10 位帧,LinPlayer 里渲染跟不上:
    # 同一段 HEVC 10bit 动画,RIFE 2 倍 2 秒丢 37 帧;锁 8 位后 8 秒丢 0 帧(mpv.exe 不渲染时两者都不丢)
    clip = FMT_CTRL(clip, h_max=h_max, fmt_pix=1)
    if algo == "rife":
        clip = RIFE_ORT_HUB(
            clip, model=46, turbo=True, fps_in=fps, fps_num=multi, fps_den=1, sc_mode=1, gpu_t=2,
            backend_type="dml", func_name="LinPlayer", vsmlrt=vsmlrt,
            backend_param=lambda ext: vsmlrt.BackendV2.ORT_DML(num_streams=2, fp16=bool(ext), device_id=gpu))
    else:
        # DRBA_HUB 只拿 gpu 做 0/1/2 校验,真正用的设备号在 backend_param 里
        clip = DRBA_HUB(
            clip, model=2, turbo=2, fps_in=fps, fps_num=multi, fps_den=1, sc_mode=0, gpu=0, gpu_t=2,
            backend_type="dml", func_name="LinPlayer", vsmlrt=vsmlrt,
            backend_param=lambda *_: {"halfm": True, "backend": vsmlrt.BackendV2.ORT_DML(
                num_streams=2, fp16=False, device_id=gpu)})
clip.set_output()
