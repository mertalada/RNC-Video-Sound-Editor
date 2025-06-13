//
//  RNCVideoEditor.swift
//  soundsnap
//
//  iOS tarafındaki native modül; AVAssetExportSession üzerinden
//  video’dan ses ayırma & ses + video birleştirme işlemlerini yapar.
//

import Foundation
import AVFoundation
import React

@objc(RNCVideoEditor)
class RNCVideoEditor: NSObject {

  // ----------------------------------------------------------
  // **1) Aktif çalışan export oturumunu saklamak için property**
  // ----------------------------------------------------------
  private var currentExporter: AVAssetExportSession? = nil

  // ----------------------------------
  // 1) Videodan Ses Ayırma (M4A Çıktı)
  // ----------------------------------
  @objc(separateAudioVideo:resolver:rejecter:)
  func separateAudioVideo(
    _ videoPath: String,
    resolver: @escaping RCTPromiseResolveBlock,
    rejecter: @escaping RCTPromiseRejectBlock
  ) {
    print("⏳ [Separate] Starting separation for video: \(videoPath)")
    let url = URL(fileURLWithPath: videoPath)
    let asset = AVAsset(url: url)

    // “tracks” anahtarını asenkron yükleyelim
    asset.loadValuesAsynchronously(forKeys: ["tracks"]) {
      var loadError: NSError? = nil
      let status = asset.statusOfValue(forKey: "tracks", error: &loadError)

      switch status {
      case .loaded:
        // Şimdi track’ler hazır, audio track’i kontrol edelim
        let audioTracks = asset.tracks(withMediaType: .audio)
        guard !audioTracks.isEmpty else {
          print("🚫 [Separate] No audio tracks found in video")
          rejecter("no_audio", "No audio track found in video", nil)
          return
        }
        print("✅ [Separate] Audio track found: \(audioTracks.count)")

        // Çıktı dosya yolunu belirleyelim:
        let uid = UUID().uuidString
        let outputFilename = "extractedAudio_\(uid).m4a"
        let outputURL = URL(fileURLWithPath: NSTemporaryDirectory())
                          .appendingPathComponent(outputFilename)

        // Eğer daha önce aynı isimde bir dosya varsa sil
        if FileManager.default.fileExists(atPath: outputURL.path) {
          try? FileManager.default.removeItem(at: outputURL)
          print("ℹ️ [Separate] Removed existing file at: \(outputURL.path)")
        }

        // ExportSession oluştur
        guard let exporter = AVAssetExportSession(asset: asset, presetName: AVAssetExportPresetAppleM4A) else {
          print("🚫 [Separate] Could not create export session")
          rejecter("export_session_failed", "Could not create export session", nil)
          return
        }

        exporter.outputFileType = .m4a
        exporter.outputURL = outputURL
        exporter.timeRange = CMTimeRange(start: .zero, duration: asset.duration)
        exporter.shouldOptimizeForNetworkUse = true

        // **Aktif exporter’ı sakla**
        self.currentExporter = exporter

        print("ℹ️ [Separate] Exporter configured, exporting to: \(outputURL.path)")

        exporter.exportAsynchronously {
          switch exporter.status {
          case .completed:
            print("✅ [Separate] Completed: \(outputURL.path)")
            // **Referansı sıfırla**
            self.currentExporter = nil
            resolver(outputURL.path)

          case .failed:
            let err = exporter.error as NSError?
            let msg = err?.localizedDescription ?? "Unknown error"
            print("🚫 [Separate] Export failed: \(msg)")
            if let ue = err {
              print("   ↪️ Underlying error = \(ue), Code = \(ue.code)")
            }
            // **Referansı sıfırla**
            self.currentExporter = nil
            rejecter("export_failed", "Failed to extract audio: \(msg)", err)

          case .cancelled:
            print("🚫 [Separate] Export cancelled")
            // **Referansı sıfırla**
            self.currentExporter = nil
            rejecter("export_cancelled", "Export cancelled", nil)

          default:
            print("🚫 [Separate] Export unknown status: \(exporter.status.rawValue)")
            // **Referansı sıfırla**
            self.currentExporter = nil
            rejecter("export_unknown", "Unknown export status", nil)
          }
        }

      case .failed, .cancelled:
        let reason = loadError?.localizedDescription ?? "Unknown load error"
        print("🚫 [Separate] Failed to load tracks: \(reason)")
        rejecter("load_tracks_failed", "Failed to load tracks: \(reason)", loadError)

      default:
        print("🚫 [Separate] Unexpected status for tracks: \(status.rawValue)")
        let e = loadError ?? NSError(domain: "RNCVideoEditor", code: -1,
                                     userInfo: [NSLocalizedDescriptionKey: "Unexpected status"])
        rejecter("load_tracks_unexpected", "Unexpected status: \(status.rawValue)", e)
      }
    }
  }


  // ----------------------------------
  // 2) Video + Yeni Ses Birleştirme (AAC olan M4A/MP4 → MP4 Çıktı)
  // ----------------------------------
  @objc(mergeAudioWithVideo:audioPath:resolver:rejecter:)
  func mergeAudioWithVideo(
    _ videoPath: String,
    audioPath: String,
    resolver: @escaping RCTPromiseResolveBlock,
    rejecter: @escaping RCTPromiseRejectBlock
  ) {
    print("⏳ [Merge] Starting merge: video=\(videoPath), audio=\(audioPath)")
    let videoURL = URL(fileURLWithPath: videoPath)
    let audioURL = URL(fileURLWithPath: audioPath)

    let videoAsset = AVAsset(url: videoURL)
    let audioAsset = AVAsset(url: audioURL)

    // Hem video hem de audio için “tracks” anahtarını asenkron yükleyelim
    let group = DispatchGroup()
    var videoLoadError: NSError? = nil
    var audioLoadError: NSError? = nil
    var videoStatus: AVKeyValueStatus = .unknown
    var audioStatus: AVKeyValueStatus = .unknown

    // Video asset “tracks” yüklemesi
    group.enter()
    videoAsset.loadValuesAsynchronously(forKeys: ["tracks"]) {
      var err: NSError? = nil
      videoStatus = videoAsset.statusOfValue(forKey: "tracks", error: &err)
      videoLoadError = err
      group.leave()
    }

    // Audio asset “tracks” yüklemesi
    group.enter()
    audioAsset.loadValuesAsynchronously(forKeys: ["tracks"]) {
      var err: NSError? = nil
      audioStatus = audioAsset.statusOfValue(forKey: "tracks", error: &err)
      audioLoadError = err
      group.leave()
    }

    // Yüklemeler tamamlanınca merger adımına geç
    group.notify(queue: DispatchQueue.global(qos: .userInitiated)) {
      // Video’nun yükleme durumu
      if videoStatus != .loaded {
        let msg = videoLoadError?.localizedDescription ?? "Could not load video tracks"
        print("🚫 [Merge] Video load failed: \(msg)")
        rejecter("video_load_failed", "Failed to load video tracks: \(msg)", videoLoadError)
        return
      }
      // Audio’nun yükleme durumu
      if audioStatus != .loaded {
        let msg = audioLoadError?.localizedDescription ?? "Could not load audio tracks"
        print("🚫 [Merge] Audio load failed: \(msg)")
        rejecter("audio_load_failed", "Failed to load audio tracks: \(msg)", audioLoadError)
        return
      }

      // Artık track bilgileri hazır; track’leri seçelim:
      guard let videoTrack = videoAsset.tracks(withMediaType: .video).first else {
        print("🚫 [Merge] No video track found")
        rejecter("merge_no_video", "No video track found", nil)
        return
      }
      guard let audioTrack = audioAsset.tracks(withMediaType: .audio).first else {
        print("🚫 [Merge] No audio track found")
        rejecter("merge_no_audio", "No audio track found", nil)
        return
      }
      print("✅ [Merge] Loaded both video & audio tracks")

      // Kompozisyon oluştur
      let mixComposition = AVMutableComposition()
      guard let videoCompositionTrack = mixComposition.addMutableTrack(
              withMediaType: .video,
              preferredTrackID: kCMPersistentTrackID_Invalid) else {
        print("🚫 [Merge] Could not create video composition track")
        rejecter("composition_error", "Could not create video composition track", nil)
        return
      }
      guard let audioCompositionTrack = mixComposition.addMutableTrack(
              withMediaType: .audio,
              preferredTrackID: kCMPersistentTrackID_Invalid) else {
        print("🚫 [Merge] Could not create audio composition track")
        rejecter("composition_error", "Could not create audio composition track", nil)
        return
      }

      // Orijinal video’nun orientation’ini koru
      videoCompositionTrack.preferredTransform = videoTrack.preferredTransform
      print("ℹ️ [Merge] Applied video preferredTransform")

      // Kompozisyona track’leri ekle
      do {
        let duration = videoAsset.duration
        try videoCompositionTrack.insertTimeRange(
          CMTimeRange(start: .zero, duration: duration),
          of: videoTrack,
          at: .zero
        )
        try audioCompositionTrack.insertTimeRange(
          CMTimeRange(start: .zero, duration: duration),
          of: audioTrack,
          at: .zero
        )
        print("✅ [Merge] Inserted video & audio into composition")
      } catch {
        let errMsg = error.localizedDescription
        print("🚫 [Merge] Insert tracks failed: \(errMsg)")
        rejecter("insert_error", "Failed to insert tracks: \(errMsg)", error as NSError)
        return
      }

      // Çıktı dosyasını temp içine koyalım
      let uid = UUID().uuidString
      let outputFilename = "mergedVideo_\(uid).mp4"
      let outputURL = URL(fileURLWithPath: NSTemporaryDirectory())
                        .appendingPathComponent(outputFilename)

      // Eğer aynı isimde bir dosya varsa sil
      if FileManager.default.fileExists(atPath: outputURL.path) {
        try? FileManager.default.removeItem(at: outputURL)
        print("ℹ️ [Merge] Removed existing file at: \(outputURL.path)")
      }

      // ExportSession oluştur
      guard let exporter = AVAssetExportSession(
              asset: mixComposition,
              presetName: AVAssetExportPresetHighestQuality
            ) else {
        print("🚫 [Merge] Could not create export session")
        rejecter("export_session_failed", "Could not create export session", nil)
        return
      }

      exporter.outputFileType = .mp4
      exporter.outputURL = outputURL
      exporter.shouldOptimizeForNetworkUse = true

      // **Aktif exporter’ı sakla**
      self.currentExporter = exporter

      print("ℹ️ [Merge] Exporter configured, exporting to: \(outputURL.path)")

      exporter.exportAsynchronously {
        switch exporter.status {
        case .completed:
          print("✅ [Merge] Completed: \(outputURL.path)")
          // **Referansı sıfırla**
          self.currentExporter = nil
          resolver(outputURL.path)

        case .failed:
          let err = exporter.error as NSError?
          let msg = err?.localizedDescription ?? "Unknown error"
          print("🚫 [Merge] Export failed: \(msg)")
          if let ue = err {
            print("   ↪️ Underlying error = \(ue), Code = \(ue.code)")
          }
          // **Referansı sıfırla**
          self.currentExporter = nil
          rejecter("merge_failed", "Failed to merge audio with video: \(msg)", err)

        case .cancelled:
          print("🚫 [Merge] Export cancelled")
          // **Referansı sıfırla**
          self.currentExporter = nil
          rejecter("merge_cancelled", "Merge cancelled", nil)

        default:
          print("🚫 [Merge] Export unknown status: \(exporter.status.rawValue)")
          // **Referansı sıfırla**
          self.currentExporter = nil
          rejecter("merge_unknown", "Unknown merge status", nil)
        }
      }
    }
  }


  // --------------------------------------------------------
  // **3) JS tarafından çağrılacak: Mevcut export’u iptal etme**
  // --------------------------------------------------------
  @objc(cancelProcessing)
  func cancelProcessing() {
    if let exporter = self.currentExporter {
      print("ℹ️ [cancelProcessing] Cancelling current export…")
      exporter.cancelExport()
      self.currentExporter = nil
    } else {
      print("ℹ️ [cancelProcessing] No active exporter to cancel.")
    }
  }

  // Bu metodu React Native modülü olarak expose etmek için
  @objc
  static func requiresMainQueueSetup() -> Bool {
    return false
  }
}
