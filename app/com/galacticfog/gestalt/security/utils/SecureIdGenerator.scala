package com.galacticfog.gestalt.security.utils

import java.security.SecureRandom

object SecureIdGenerator {

  val random = new SecureRandom()

  val alpha62 = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
  val alpha64 = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789/+"

  def genId62(len: Int) = {
    val id = new StringBuilder(len)
    (1 to len) foreach(_ => id += alpha62.charAt(random.nextInt(alpha62.size)))
    id.toString
  }

  def genId64(len: Int) = {
    val id = new StringBuilder(len)
    (1 to len) foreach(_ => id += alpha64.charAt(random.nextInt(alpha64.size)))
    id.toString
  }

}
