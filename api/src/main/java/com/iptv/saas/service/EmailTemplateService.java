package com.iptv.saas.service;

import com.iptv.saas.domain.Invoice;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

@Service
public class EmailTemplateService {
    private static final DateTimeFormatter DATE = DateTimeFormatter
            .ofPattern("dd MMMM yyyy", Locale.FRENCH)
            .withZone(ZoneId.systemDefault());

    private final String companyName;
    private final String companyEmail;

    public EmailTemplateService(
            @Value("${app.mail.from-name:Nexora}") String companyName,
            @Value("${app.mail.from-address:noreply@nexora.local}") String companyEmail
    ) {
        this.companyName = companyName;
        this.companyEmail = companyEmail;
    }

    public String connectionTest() {
        return layout(
                "Connexion confirmée",
                "Votre serveur SMTP est correctement relié à Nexora.",
                """
                <div style="padding:22px;border:1px solid #28312b;background:#101512">
                  <p style="margin:0;color:#59d492;font-size:12px;font-weight:700;letter-spacing:.12em">SIGNAL OPÉRATIONNEL</p>
                  <p style="margin:10px 0 0;color:#d9ddd7;line-height:1.6">Les e-mails transactionnels peuvent maintenant être envoyés depuis la plateforme.</p>
                </div>
                """
        );
    }

    public String otp(String title, String introduction, String code, long validMinutes) {
        return layout(
                title,
                introduction,
                """
                <div style="margin:28px 0;padding:28px;text-align:center;border:1px solid #3b453e;background:#101512">
                  <p style="margin:0 0 10px;color:#8b938c;font-size:11px;letter-spacing:.14em">VOTRE CODE</p>
                  <strong style="color:#e7c36d;font-family:Georgia,serif;font-size:42px;letter-spacing:.18em">%s</strong>
                  <p style="margin:14px 0 0;color:#8b938c;font-size:12px">Valable pendant %d minutes.</p>
                </div>
                <p style="color:#8b938c;font-size:12px;line-height:1.6">Si vous n’êtes pas à l’origine de cette demande, ignorez simplement cet e-mail.</p>
                """.formatted(escape(code), validMinutes)
        );
    }

    public String supportOpened(Long ticketId, String subject) {
        return layout(
                "Ticket reçu",
                "Notre équipe a bien reçu votre demande.",
                detailCard(
                        "TICKET #" + ticketId,
                        subject,
                        "Statut : ouvert"
                ) + button("Ouvrir mon espace Nexora", "http://localhost:8080/")
        );
    }

    public String supportReply(Long ticketId, String subject) {
        return layout(
                "Nouvelle réponse du support",
                "Une réponse vient d’être ajoutée à votre conversation.",
                detailCard(
                        "TICKET #" + ticketId,
                        subject,
                        "Connectez-vous pour consulter le message."
                ) + button("Consulter la réponse", "http://localhost:8080/")
        );
    }

    public String invoice(Invoice invoice) {
        return invoiceClassic(invoice);
    }

    public String invoiceClassic(Invoice invoice) {
        return """
                <!DOCTYPE html>
                <html lang="fr">
                <head><meta charset="utf-8"/><title>Facture %s</title></head>
                <body style="margin:0;background:#f4f6f8;color:#111827;font-family:Arial,Helvetica,sans-serif;">
                    <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" style="background:#f4f6f8;padding:28px 12px;">
                        <tr>
                            <td align="center">
                                <table role="presentation" width="720" cellspacing="0" cellpadding="0" style="max-width:720px;width:100%%;background:#ffffff;border:1px solid #e5e7eb;">
                                    <tr>
                                        <td style="padding:28px 32px;background:#111827;color:#ffffff;">
                                            <table role="presentation" width="100%%" cellspacing="0" cellpadding="0">
                                                <tr>
                                                    <td>
                                                        <div style="font-size:22px;font-weight:700;">%s</div>
                                                        <div style="font-size:13px;color:#d1d5db;margin-top:6px;">%s</div>
                                                    </td>
                                                    <td align="right">
                                                        <div style="font-size:28px;font-weight:700;letter-spacing:1px;">FACTURE</div>
                                                        <div style="font-size:13px;color:#d1d5db;margin-top:6px;">%s</div>
                                                    </td>
                                                </tr>
                                            </table>
                                        </td>
                                    </tr>

                                    <tr>
                                        <td style="padding:28px 32px 12px;">
                                            <table role="presentation" width="100%%" cellspacing="0" cellpadding="0">
                                                <tr>
                                                    <td valign="top" style="width:50%%;padding-right:16px;">
                                                        <div style="font-size:12px;text-transform:uppercase;color:#6b7280;font-weight:700;">Facture à</div>
                                                        <div style="font-size:16px;font-weight:700;margin-top:8px;">%s</div>
                                                        <div style="font-size:14px;color:#374151;margin-top:4px;">%s</div>
                                                    </td>
                                                    <td valign="top" align="right" style="width:50%%;padding-left:16px;">
                                                        <table role="presentation" cellspacing="0" cellpadding="0" style="margin-left:auto;font-size:14px;color:#374151;">
                                                            <tr>
                                                                <td style="padding:3px 12px 3px 0;color:#6b7280;">Date facture</td>
                                                                <td style="padding:3px 0;font-weight:700;">%s</td>
                                                            </tr>
                                                            <tr>
                                                                <td style="padding:3px 12px 3px 0;color:#6b7280;">Référence paiement</td>
                                                                <td style="padding:3px 0;font-weight:700;">%s</td>
                                                            </tr>
                                                            <tr>
                                                                <td style="padding:3px 12px 3px 0;color:#6b7280;">Statut</td>
                                                                <td style="padding:3px 0;font-weight:700;color:%s;">%s</td>
                                                            </tr>
                                                        </table>
                                                    </td>
                                                </tr>
                                            </table>
                                        </td>
                                    </tr>

                                    <tr>
                                        <td style="padding:16px 32px 6px;">
                                            <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" style="border-collapse:collapse;">
                                                <tr>
                                                    <th align="left" style="padding:12px;background:#f3f4f6;border:1px solid #e5e7eb;font-size:13px;color:#374151;">Description</th>
                                                    <th align="center" style="padding:12px;background:#f3f4f6;border:1px solid #e5e7eb;font-size:13px;color:#374151;">Période</th>
                                                    <th align="right" style="padding:12px;background:#f3f4f6;border:1px solid #e5e7eb;font-size:13px;color:#374151;">Montant</th>
                                                </tr>
                                                <tr>
                                                    <td style="padding:14px 12px;border:1px solid #e5e7eb;font-size:14px;">
                                                        <strong>Abonnement %s</strong><br/>
                                                        <span style="color:#6b7280;">Accès IPTV selon les limites du plan</span>
                                                    </td>
                                                    <td align="center" style="padding:14px 12px;border:1px solid #e5e7eb;font-size:14px;color:#374151;">
                                                        %s<br/>
                                                        au<br/>
                                                        %s
                                                    </td>
                                                    <td align="right" style="padding:14px 12px;border:1px solid #e5e7eb;font-size:14px;font-weight:700;">
                                                        %s
                                                    </td>
                                                </tr>
                                            </table>
                                        </td>
                                    </tr>

                                    <tr>
                                        <td style="padding:12px 32px 28px;">
                                            <table role="presentation" width="100%%" cellspacing="0" cellpadding="0">
                                                <tr>
                                                    <td valign="top" style="font-size:13px;color:#6b7280;line-height:1.55;">
                                                        Moyen de paiement : <strong>%s</strong><br/>
                                                        Votre abonnement est actif jusqu'au <strong>%s</strong>.
                                                    </td>
                                                    <td align="right" valign="top">
                                                        <table role="presentation" cellspacing="0" cellpadding="0" style="min-width:240px;">
                                                            <tr>
                                                                <td style="padding:8px 12px;color:#6b7280;border-bottom:1px solid #e5e7eb;">Sous-total</td>
                                                                <td align="right" style="padding:8px 0 8px 12px;border-bottom:1px solid #e5e7eb;">%s</td>
                                                            </tr>
                                                            <tr>
                                                                <td style="padding:10px 12px;font-size:16px;font-weight:700;">Total payé</td>
                                                                <td align="right" style="padding:10px 0 10px 12px;font-size:16px;font-weight:700;">%s</td>
                                                            </tr>
                                                        </table>
                                                    </td>
                                                </tr>
                                            </table>
                                        </td>
                                    </tr>

                                    <tr>
                                        <td style="padding:18px 32px;background:#f9fafb;border-top:1px solid #e5e7eb;color:#6b7280;font-size:12px;line-height:1.5;">
                                            Merci pour votre confiance. Conservez cette facture comme preuve de paiement.
                                            Pour toute question, contactez %s.
                                        </td>
                                    </tr>
                                </table>
                            </td>
                        </tr>
                    </table>
                </body>
                </html>
                """.formatted(
                escape(invoice.invoiceNumber),
                escape(companyName),
                escape(companyEmail),
                escape(invoice.invoiceNumber),
                escape(invoiceCustomerName(invoice)),
                escape(invoiceCustomerEmail(invoice)),
                formatDate(invoice.issuedAt),
                escape(invoicePaymentReference(invoice)),
                escape(invoiceStatusColor(invoice)),
                escape(invoiceStatusLabel(invoice)),
                escape(invoicePlanName(invoice)),
                formatDate(invoice.issuedAt),
                formatDate(addDays(invoice.issuedAt, 30)),
                formatAmount(invoice.amount, invoice.currency),
                escape(invoicePaymentProvider(invoice)),
                formatDate(addDays(invoice.issuedAt, 30)),
                formatAmount(invoice.amount, invoice.currency),
                formatAmount(invoice.amount, invoice.currency),
                escape(companyEmail)
        );
    }

    public String invoiceModern(Invoice invoice) {
        return layout(
                "Votre facture a été émise",
                "Votre document de paiement est joint à ce message.",
                """
                <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" style="max-width:720px;margin:0 auto;background:#ffffff;border-radius:20px;overflow:hidden;box-shadow:0 10px 40px rgba(17,24,39,.08);">
                    <tr style="background:#111827;color:#ffffff;"><td style="padding:28px 32px;">
                        <div style="font-size:22px;font-weight:700;">%s</div>
                        <div style="font-size:14px;color:#d1d5db;margin-top:6px;">Facture n° %s</div>
                    </td></tr>
                    <tr><td style="padding:28px 32px;color:#111827;font-size:14px;line-height:1.75;">
                        <table role="presentation" width="100%%" cellspacing="0" cellpadding="0">
                            <tr><td style="width:50%%;vertical-align:top;padding-right:12px;">
                                <div style="font-size:12px;text-transform:uppercase;color:#6b7280;font-weight:700;">Facturé à</div>
                                <div style="font-size:18px;font-weight:700;margin-top:10px;">%s</div>
                                <div style="margin-top:6px;color:#6b7280;">%s</div>
                            </td>
                            <td style="width:50%%;vertical-align:top;padding-left:12px;">
                                <div style="background:#f8fafc;border:1px solid #e5e7eb;padding:16px;border-radius:12px;">
                                    <div style="font-size:12px;text-transform:uppercase;color:#6b7280;font-weight:700;margin-bottom:10px;">Détails</div>
                                    <div style="margin-bottom:8px;"><strong>Date</strong><br>%s</div>
                                    <div style="margin-bottom:8px;"><strong>Réf. paiement</strong><br>%s</div>
                                    <div><strong>Statut</strong><br><span style="color:%s;font-weight:700;">%s</span></div>
                                </div>
                            </td></tr>
                        </table>
                        <div style="margin-top:28px;border:1px solid #e5e7eb;border-radius:16px;overflow:hidden;">
                            <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" style="border-collapse:collapse;">
                                <tr style="background:#f3f4f6;color:#374151;"><th align="left" style="padding:18px 18px;">Description</th><th align="center" style="padding:18px 18px;">Période</th><th align="right" style="padding:18px 18px;">Montant</th></tr>
                                <tr><td style="padding:18px;vertical-align:top;color:#111827;"><strong>Abonnement %s</strong><br><span style="color:#6b7280;">Accès IPTV premium</span></td>
                                    <td align="center" style="padding:18px;color:#374151;">%s<br>au<br>%s</td>
                                    <td align="right" style="padding:18px;color:#111827;font-weight:700;">%s</td>
                                </tr>
                            </table>
                        </div>
                        <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" style="margin-top:24px;">
                            <tr><td style="padding:16px;background:#f9fafb;border:1px solid #e5e7eb;border-radius:16px;color:#6b7280;">Moyen de paiement : <strong>%s</strong></td>
                                <td align="right" style="padding:16px;background:#f9fafb;border:1px solid #e5e7eb;border-radius:16px;color:#111827;font-weight:700;">Total payé : %s</td></tr>
                        </table>
                        <p style="margin:24px 0 0;color:#6b7280;font-size:13px;line-height:1.6;">Pour toute question, contactez <a href="mailto:%s" style="color:#111827;text-decoration:none;">%s</a>.</p>
                    </td></tr>
                </table>
                """.formatted(
                        escape(companyName),
                        escape(invoice.invoiceNumber),
                        escape(invoiceCustomerName(invoice)),
                        escape(invoiceCustomerEmail(invoice)),
                        formatDate(invoice.issuedAt),
                        escape(invoicePaymentReference(invoice)),
                        escape(invoiceStatusColor(invoice)),
                        escape(invoiceStatusLabel(invoice)),
                        escape(invoicePlanName(invoice)),
                        formatDate(invoice.issuedAt),
                        formatDate(addDays(invoice.issuedAt, 30)),
                        formatAmount(invoice.amount, invoice.currency),
                        escape(invoicePaymentProvider(invoice)),
                        formatAmount(invoice.amount, invoice.currency),
                        escape(companyEmail),
                        escape(companyEmail)
                )
        );
    }

    public String invoiceCompact(Invoice invoice) {
        return layout(
                "Facture prête",
                "Référez-vous à votre facture jointe pour le détail du paiement.",
                """
                <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" style="max-width:720px;margin:0 auto;background:#ffffff;border:1px solid #e5e7eb;border-radius:16px;overflow:hidden;">
                    <tr><td style="padding:24px 28px;background:#111827;color:#ffffff;">
                        <div style="font-size:20px;font-weight:700;">%s</div>
                        <div style="font-size:13px;color:#94a3b8;margin-top:8px;">Facture %s</div>
                    </td></tr>
                    <tr><td style="padding:24px 28px;color:#111827;font-size:14px;line-height:1.75;">
                        <table role="presentation" width="100%%" cellspacing="0" cellpadding="0">
                            <tr><td style="width:50%%;vertical-align:top;padding-right:16px;">
                                <div style="font-weight:700;color:#111827;">Client</div>
                                <div>%s</div>
                                <div style="margin-top:8px;color:#6b7280;">%s</div>
                            </td>
                            <td style="width:50%%;vertical-align:top;padding-left:16px;">
                                <div style="font-weight:700;color:#111827;">Statut</div>
                                <div style="color:%s;">%s</div>
                                <div style="margin-top:14px;color:#6b7280;">%s</div>
                            </td></tr>
                        </table>
                        <div style="margin-top:22px;padding:18px;border:1px solid #e5e7eb;border-radius:14px;background:#f8fafc;">
                            <div style="font-weight:700;color:#111827;">Total</div>
                            <div style="font-size:24px;font-weight:700;color:#111827;margin-top:8px;">%s</div>
                        </div>
                    </td></tr>
                    <tr><td style="padding:18px 28px 28px;color:#6b7280;font-size:13px;line-height:1.6;">
                        Paiement : <strong>%s</strong><br>
                        Date d’émission : <strong>%s</strong><br>
                        Référence : <strong>%s</strong>
                    </td></tr>
                </table>
                """.formatted(
                        escape(companyName),
                        escape(invoice.invoiceNumber),
                        escape(invoiceCustomerName(invoice)),
                        escape(invoiceCustomerEmail(invoice)),
                        escape(invoiceStatusColor(invoice)),
                        escape(invoiceStatusLabel(invoice)),
                        escape(invoicePlanName(invoice)),
                        formatAmount(invoice.amount, invoice.currency),
                        escape(invoicePaymentProvider(invoice)),
                        formatDate(invoice.issuedAt),
                        escape(invoicePaymentReference(invoice))
                )
        );
    }

    public String showcase() {
        return layout(
                "Bibliothèque d'e-mails Nexora",
                "Aperçu des modèles transactionnels actifs.",
                detailCard("SÉCURITÉ", "OTP, validation e-mail et 2FA", "Codes à durée limitée")
                        + detailCard("FACTURATION", "Factures et confirmations", "PDF joint automatiquement")
                        + detailCard("ASSISTANCE", "Ouverture et réponse support", "Suivi clair du ticket")
        );
    }

    private String invoiceCustomerName(Invoice invoice) {
        return invoice.organization == null ? "Client Nexora" : invoice.organization.name;
    }

    private String invoiceCustomerEmail(Invoice invoice) {
        if (invoice.organization != null && invoice.organization.billingEmail != null) {
            return invoice.organization.billingEmail;
        }
        return "client@nexora.local";
    }

    private String invoicePlanName(Invoice invoice) {
        if (invoice.paymentTransaction != null && invoice.paymentTransaction.plan != null && invoice.paymentTransaction.plan.name != null) {
            return invoice.paymentTransaction.plan.name;
        }
        return "Nexora";
    }

    private String invoicePaymentProvider(Invoice invoice) {
        if (invoice.paymentTransaction != null && invoice.paymentTransaction.paymentMethod != null && invoice.paymentTransaction.paymentMethod.name != null) {
            return invoice.paymentTransaction.paymentMethod.name;
        }
        return "Paiement en ligne";
    }

    private String invoicePaymentReference(Invoice invoice) {
        if (invoice.paymentTransaction != null && invoice.paymentTransaction.paymentReference != null) {
            return invoice.paymentTransaction.paymentReference;
        }
        return invoice.invoiceNumber;
    }

    private String invoiceStatusLabel(Invoice invoice) {
        if (invoice.paymentTransaction != null && invoice.paymentTransaction.status != null) {
            switch (invoice.paymentTransaction.status) {
                case VERIFIED:
                    return "Payée";
                case PENDING:
                    return "En attente";
                case REJECTED, EXPIRED:
                    return "Refusée";
                default:
                    return invoice.status == null ? "—" : escape(invoice.status.name());
            }
        }
        if (invoice.status == null) {
            return "—";
        }
        return switch (invoice.status) {
            case SENT, DOWNLOADED -> "Payée";
            case ISSUED, DRAFT -> "Émise";
            default -> escape(invoice.status.name());
        };
    }

    private String invoiceStatusColor(Invoice invoice) {
        String label = invoiceStatusLabel(invoice);
        return switch (label) {
            case "Payée" -> "#047857";
            case "En attente" -> "#b45309";
            case "Refusée" -> "#b91c1c";
            default -> "#1f2937";
        };
    }

    private Instant addDays(Instant instant, long days) {
        return instant == null ? Instant.now() : instant.plus(days, ChronoUnit.DAYS);
    }

    private String detailCard(String kicker, String title, String note) {
        return """
               <div style="margin:0 0 12px;padding:18px;border:1px solid #28312b;background:#101512">
                 <p style="margin:0;color:#e7c36d;font-size:10px;font-weight:700;letter-spacing:.12em">%s</p>
                 <p style="margin:7px 0 4px;color:#f1f0e8;font-family:Georgia,serif;font-size:20px">%s</p>
                 <p style="margin:0;color:#8b938c;font-size:12px">%s</p>
               </div>
               """.formatted(escape(kicker), escape(title), escape(note));
    }

    private String button(String label, String url) {
        return """
               <p style="margin:25px 0 0"><a href="%s" style="display:inline-block;padding:13px 18px;color:#11130f;background:#e7c36d;text-decoration:none;font-weight:700">%s</a></p>
               """.formatted(escape(url), escape(label));
    }

    private String layout(String title, String preheader, String content) {
        return """
               <!doctype html>
               <html lang="fr">
               <body style="margin:0;padding:0;background:#080b0a;font-family:Arial,sans-serif;color:#f1f0e8">
                 <div style="display:none;max-height:0;overflow:hidden">%s</div>
                 <table role="presentation" style="width:100%%;border-collapse:collapse;background:#080b0a">
                   <tr><td align="center" style="padding:34px 15px">
                     <table role="presentation" style="width:100%%;max-width:620px;border-collapse:collapse">
                       <tr><td style="padding:0 0 24px">
                         <span style="display:inline-block;padding:8px 11px;color:#10120f;background:#e7c36d;font-family:Georgia,serif;font-size:25px;font-weight:700">N</span>
                         <strong style="margin-left:10px;color:#f1f0e8;letter-spacing:.22em">NEXORA</strong>
                       </td></tr>
                       <tr><td style="padding:34px;border:1px solid #202722;background:#0d110f">
                         <p style="margin:0 0 9px;color:#e7c36d;font-size:10px;font-weight:700;letter-spacing:.14em">MESSAGE TRANSACTIONNEL</p>
                         <h1 style="margin:0 0 14px;color:#f1f0e8;font-family:Georgia,serif;font-size:34px;font-weight:400">%s</h1>
                         <p style="margin:0 0 26px;color:#a2aaa3;font-size:14px;line-height:1.6">%s</p>
                         %s
                       </td></tr>
                       <tr><td style="padding:20px 6px;color:#626a64;font-size:10px;line-height:1.6">
                         Nexora · Message automatique, merci de ne pas répondre directement.
                       </td></tr>
                     </table>
                   </td></tr>
                 </table>
               </body>
               </html>
               """.formatted(escape(preheader), escape(title), escape(preheader), content);
    }

    private String formatDate(Instant instant) {
        return instant == null ? "—" : DATE.format(instant);
    }

    private String formatAmount(BigDecimal amount, String currency) {
        NumberFormat format = NumberFormat.getNumberInstance(Locale.FRENCH);
        format.setMinimumFractionDigits(2);
        format.setMaximumFractionDigits(2);
        return format.format(amount == null ? BigDecimal.ZERO : amount) + " " + escape(currency);
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
