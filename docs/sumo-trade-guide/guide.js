"use strict";

const montage = JSON.parse(document.getElementById("clip-data").textContent);
const videos = Array.from(document.querySelectorAll("video"));
const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
const msLabel = ms => ms < 0 ? "−" + Math.abs(ms) + " ms" : "+" + ms + " ms";

for (const clip of montage) {
  const chapter = document.getElementById(clip.key);
  const video = chapter.querySelector("video");
  const controls = chapter.querySelector(".controls");
  const scrub = controls.querySelector("input");
  const clock = controls.querySelector(".clock");
  const play = controls.querySelector(".play");
  const speed = controls.querySelector(".speed");
  const readout = chapter.querySelector(".readout");
  const cues = Array.from(chapter.querySelectorAll(".cues button"));
  const explanation = chapter.querySelector(".cue-explanation");
  const beforeStart = clip.prior - clip.start_ms, afterStart = clip.hit - clip.start_ms;
  const rate = () => Number(speed.value);
  const maxFrame = clip.frames - 1;
  let pendingSeek = null, pinnedCue = null, selectedCue = -2, lastSample, scheduledFrame = 0;

  speed.value = reducedMotion ? "0.25" : "0.5";
  video.controls = false;
  chapter.classList.add("enhanced");
  scrub.max = maxFrame;

  const waiters = [scrub, play, ...controls.querySelectorAll("[data-step]"), ...cues];
  const frameOf = t => Math.min(maxFrame, Math.max(0, t));
  const timeOf = f => Math.min(video.duration || clip.frames / clip.fps, f / clip.fps + 0.00025);

  function tag(name, text) {
    const node = document.createElement(name);
    node.textContent = text;
    return node;
  }
  function sync() {
    const ms = Math.round(video.currentTime * 1000);
    scrub.value = frameOf(Math.round(video.currentTime * clip.fps));
    clock.textContent = `Frame ${msLabel(ms - afterStart)}`;
    scrub.setAttribute("aria-valuetext", `${msLabel(ms - afterStart)} relative to the big hit`);
    const before = ms < afterStart;
    const steps = before ? clip.before : clip.after, anchor = before ? beforeStart : afterStart;
    const sample = steps.findLast(move => move.ms <= ms - anchor);
    if (sample !== lastSample || !readout.textContent) {
      lastSample = sample;
      if (sample) {
        const hold = sample.forward > 0 ? "W down" : sample.forward < 0 ? "S down" : "W up";
        readout.replaceChildren(tag("strong",before ? "Previous hit" : "Big hit"),
          tag("span",hold),tag("span",sample.ground ? "Grounded at move start" : "Airborne at move start"),
          tag("span",sample.sprint ? "Sprint on" : "Sprint off"),
          tag("strong",sample.cumulative.toFixed(2) + " blocks"));
      } else {
        readout.textContent = "Movement readout starts at the first recorded step after the hit.";
      }
    }
    const nearestCue = pinnedCue ?? clip.cues.findLastIndex(cue => cue.at - clip.start_ms <= ms + 1);
    if (nearestCue !== selectedCue) {
      selectedCue = nearestCue;
      cues.forEach((button,index)=>button.setAttribute("aria-pressed",String(index===nearestCue)));
      explanation.textContent = nearestCue >= 0 ? clip.cues[nearestCue].text : "Choose an event to pause at that moment.";
    }
  }
  function syncDuringPlayback() {
    sync();
    if (!video.paused && !video.ended) scheduledFrame = requestAnimationFrame(syncDuringPlayback);
  }
  function seek(frame, pause = true, cue = null) {
    pinnedCue = cue;
    if (pause) video.pause();
    frame = frameOf(frame);
    if (video.readyState >= 1) video.currentTime = timeOf(frame);
    else pendingSeek = {frame, cue};
    sync();
  }
  async function toggle() {
    if (!video.paused) { video.pause(); return; }
    if (video.ended || video.duration - video.currentTime < 0.04) seek(0, false);
    video.playbackRate = rate();
    try { await video.play(); }
    catch { play.textContent = "Play"; }
  }
  for (const [index, button] of cues.entries()) {
    const cue = clip.cues[index];
    button.addEventListener("click", () => seek(Math.ceil((cue.at - clip.start_ms) * clip.fps / 1000), true, index));
    const pin = document.createElement("span");
    pin.dataset.tone = cue.kind;
    pin.style.left = (100 * (cue.at - clip.start_ms) / (clip.frames * 1000 / clip.fps)).toFixed(3) + "%";
    controls.querySelector(".track-marks").append(pin);
  }
  for (const step of controls.querySelectorAll("[data-step]")) step.addEventListener("click", () => seek(Math.round(video.currentTime * clip.fps) + Number(step.dataset.step)));
  for (const still of chapter.querySelectorAll("[data-seek]")) still.addEventListener("click", () => seek(Math.ceil((Number(still.dataset.seek) - clip.start_ms)*clip.fps/1000)));
  scrub.addEventListener("input", () => seek(Number(scrub.value)));
  speed.addEventListener("change", () => { video.playbackRate = rate(); });
  play.addEventListener("click", toggle);
  video.addEventListener("click", toggle);
  video.addEventListener("loadedmetadata", () => {
    video.playbackRate = rate();
    if (pendingSeek !== null) {
      const {frame, cue} = pendingSeek;
      pendingSeek = null;
      seek(frame, true, cue);
    }
    sync();
  });
  video.addEventListener("play", () => {
    for (const other of videos) if (other !== video) other.pause();
    pinnedCue = null;
    play.textContent = "Pause";
    cancelAnimationFrame(scheduledFrame);
    syncDuringPlayback();
  });
  for (const event of ["pause", "ended", "seeked", "timeupdate"]) video.addEventListener(event, () => {
    if (video.paused || video.ended) { cancelAnimationFrame(scheduledFrame); play.textContent = "Play"; }
    sync();
  });
  video.addEventListener("error", () => {
    chapter.classList.add("is-video-error");
    for (const el of waiters) el.disabled = true;
  });
  sync();
}
