import Foundation
import AVFoundation
import React

@objc(RNCVideoEditor)
class RNCVideoEditor: NSObject {

  @objc(separateAudioVideo:resolver:rejecter:)
  func separateAudioVideo(_ videoPath: String, resolver: @escaping RCTPromiseResolveBlock, rejecter: @escaping RCTPromiseRejectBlock) {
    print("⏳ Ses çıkarma işlemi başladı. Video yolu: \(videoPath)")
    let url = URL(fileURLWithPath: videoPath)
    let asset = AVAsset(url: url)

    let audioTracks = asset.tracks(withMediaType: .audio)
    if audioTracks.isEmpty {
      print("🚫 Ses parçası bulunamadı.")
      rejecter("no_audio", "No audio track found in video", nil as NSError?)
      return
    } else {
      print("✅ Ses parçası bulundu: \(audioTracks)")
    }

    let audioFileName = "extractedAudio_\(UUID().uuidString).m4a"
    let outputURL = URL(fileURLWithPath: NSTemporaryDirectory().appending(audioFileName))
    
    guard let exporter = AVAssetExportSession(asset: asset, presetName: AVAssetExportPresetAppleM4A) else {
      print("🚫 AVAssetExportSession oluşturulamadı.")
      rejecter("export_session_failed", "Could not create export session", nil as NSError?)
      return
    }

    exporter.outputFileType = .m4a
    exporter.outputURL = outputURL
    exporter.timeRange = CMTimeRange(start: .zero, duration: asset.duration)

    exporter.exportAsynchronously {
      switch exporter.status {
      case .completed:
        print("✅ Ses çıkarıldı: \(outputURL.path)")
        resolver(outputURL.path)
      case .failed:
        print("🚫 Export başarısız: \(exporter.error?.localizedDescription ?? "Bilinmeyen hata")")
        rejecter("export_failed", "Failed to extract audio", exporter.error as NSError?)
      case .cancelled:
        print("🚫 Export iptal edildi.")
        rejecter("export_cancelled", "Export cancelled", nil as NSError?)
      default:
        print("🚫 Export bilinmeyen durumda: \(exporter.status.rawValue)")
        rejecter("export_unknown", "Unknown export status", nil as NSError?)
      }
    }
  }

  @objc(mergeAudioWithVideo:audioPath:resolver:rejecter:)
  func mergeAudioWithVideo(_ videoPath: String, audioPath: String, resolver: @escaping RCTPromiseResolveBlock, rejecter: @escaping RCTPromiseRejectBlock) {
    print("⏳ Ses birleştirme işlemi başladı.")
    let videoURL = URL(fileURLWithPath: videoPath)
    let audioURL = URL(fileURLWithPath: audioPath)

    let mixComposition = AVMutableComposition()
    let videoAsset = AVAsset(url: videoURL)
    let audioAsset = AVAsset(url: audioURL)

    guard let videoTrack = videoAsset.tracks(withMediaType: .video).first,
          let audioTrack = audioAsset.tracks(withMediaType: .audio).first else {
      print("🚫 Video veya ses parçası bulunamadı.")
      rejecter("track_error", "Could not load tracks", nil as NSError?)
      return
    }

    guard let videoCompositionTrack = mixComposition.addMutableTrack(withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid),
          let audioCompositionTrack = mixComposition.addMutableTrack(withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid) else {
      print("🚫 Kompozisyon oluşturulamadı.")
      rejecter("composition_error", "Could not create composition tracks", nil as NSError?)
      return
    }

    do {
      try videoCompositionTrack.insertTimeRange(CMTimeRange(start: .zero, duration: videoAsset.duration), of: videoTrack, at: .zero)
      try audioCompositionTrack.insertTimeRange(CMTimeRange(start: .zero, duration: videoAsset.duration), of: audioTrack, at: .zero)
    } catch {
      print("🚫 Parçalar eklenemedi: \(error.localizedDescription)")
      rejecter("insert_error", "Failed to insert tracks", error as NSError)
      return
    }

    let mergedFileName = "mergedVideo_\(UUID().uuidString).mov"
    let outputURL = URL(fileURLWithPath: NSTemporaryDirectory().appending(mergedFileName))
    
    guard let exporter = AVAssetExportSession(asset: mixComposition, presetName: AVAssetExportPresetHighestQuality) else {
      print("🚫 AVAssetExportSession oluşturulamadı.")
      rejecter("export_session_failed", "Could not create export session", nil as NSError?)
      return
    }

    exporter.outputFileType = .mov
    exporter.outputURL = outputURL

    exporter.exportAsynchronously {
      switch exporter.status {
      case .completed:
        print("✅ Video ve ses birleştirildi: \(outputURL.path)")
        resolver(outputURL.path)
      case .failed:
        print("🚫 Birleştirme başarısız: \(exporter.error?.localizedDescription ?? "Bilinmeyen hata")")
        rejecter("merge_failed", "Failed to merge audio with video", exporter.error as NSError?)
      case .cancelled:
        print("🚫 Birleştirme iptal edildi.")
        rejecter("merge_cancelled", "Merge cancelled", nil as NSError?)
      default:
        print("🚫 Birleştirme bilinmeyen durumda: \(exporter.status.rawValue)")
        rejecter("merge_unknown", "Unknown merge status", nil as NSError?)
      }
    }
  }
}
